package com.aqishi.toolbox.misc;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.*;
import java.util.*;

/**
 * 微信通讯录数据库读取与数据导出工具类。
 * 支持自适应表结构、下载头像和导出 Excel。
 */
public class WeChatContactReader {

    /**
     * 联系人信息 DTO
     */
    public static class ContactInfo {
        public String username;     // 原始微信号/群聊/公众号唯一 ID (UserName/username)
        public String alias;        // 自定义微信号 (Alias/alias)
        public String nickname;     // 昵称 (NickName/nickname)
        public String remark;       // 备注名 (Remark/remark/conRemark)
        public int gender;          // 性别 (0:未知, 1:男, 2:女)
        public String avatarUrl;    // 头像 URL (smallHeadImgUrl/bigHeadImgUrl/reserved2)
        public int type;            // 联系人类型 (Type/type)
        public boolean selected = false; // 是否在列表中被勾选

        public String getDisplayName() {
            if (remark != null && !remark.trim().isEmpty()) {
                return remark.trim();
            }
            if (nickname != null && !nickname.trim().isEmpty()) {
                return nickname.trim();
            }
            if (alias != null && !alias.trim().isEmpty()) {
                return alias.trim();
            }
            return username;
        }

        public String getWeChatId() {
            if (alias != null && !alias.trim().isEmpty()) {
                return alias.trim();
            }
            return username;
        }
    }

    /**
     * 进度回调接口
     */
    public interface ProgressCallback {
        void onProgress(int current, int total, String statusMessage);
    }

    /**
     * 从解密后的 SQLite 数据库中读取联系人信息（自适应不同版本的表结构和列名）
     */
    public static List<ContactInfo> readContacts(File dbFile) throws Exception {
        List<ContactInfo> contacts = new ArrayList<>();

        if (!dbFile.exists()) {
            throw new FileNotFoundException("数据库文件不存在：" + dbFile.getAbsolutePath());
        }

        // 加载 SQLite JDBC 驱动
        Class.forName("org.sqlite.JDBC");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        try (Connection conn = DriverManager.getConnection(url)) {
            // 1. 探测联系人表（Contact 或 rcontact）
            boolean hasContact = hasTable(conn, "Contact");
            boolean hasRContact = hasTable(conn, "rcontact");
            String contactTableName = null;

            if (hasContact) {
                contactTableName = "Contact";
            } else if (hasRContact) {
                contactTableName = "rcontact";
            } else {
                throw new Exception("数据库中未找到 Contact 或 rcontact 表，请确认这是微信解密后的联系人数据库文件。");
            }

            // 2. 探测联系人表中的列名（由于不同版本可能存在大小写或拼写差异）
            Set<String> cols = getTableColumns(conn, contactTableName);
            String usernameCol = findColumn(cols, "UserName", "username");
            String aliasCol = findColumn(cols, "Alias", "alias");
            String nicknameCol = findColumn(cols, "NickName", "nickname");
            String remarkCol = findColumn(cols, "Remark", "remark", "conRemark");
            String sexCol = findColumn(cols, "m_ui_Sex", "Sex", "sex");
            String typeCol = findColumn(cols, "Type", "type");

            if (usernameCol == null) {
                throw new Exception("联系人表中缺少 UserName/username 唯一标识列。");
            }

            // 3. 探测头像表（ContactHeadImgUrl 或 img_flag）
            boolean hasHeadImgUrlTable = hasTable(conn, "ContactHeadImgUrl");
            boolean hasImgFlagTable = hasTable(conn, "img_flag");

            String avatarTable = null;
            String avatarUserCol = null;
            String avatarUrlCol = null;

            if (hasHeadImgUrlTable) {
                avatarTable = "ContactHeadImgUrl";
                Set<String> avCols = getTableColumns(conn, avatarTable);
                avatarUserCol = findColumn(avCols, "usrName", "username", "usrname");
                avatarUrlCol = findColumn(avCols, "smallHeadImgUrl", "bigHeadImgUrl", "reserved2");
            } else if (hasImgFlagTable) {
                avatarTable = "img_flag";
                Set<String> avCols = getTableColumns(conn, avatarTable);
                avatarUserCol = findColumn(avCols, "username", "UserName");
                avatarUrlCol = findColumn(avCols, "reserved2", "reserved1");
            }

            // 4. 构建自适应 SQL 查询
            StringBuilder sql = new StringBuilder();
            sql.append("SELECT c.").append(usernameCol);
            
            // 使用 Map 来记录列索引位置，防止读取错误
            Map<String, Integer> colIndices = new HashMap<>();
            int selectCount = 1; // username is index 1
            
            if (aliasCol != null) {
                sql.append(", c.").append(aliasCol);
                colIndices.put("alias", ++selectCount);
            }
            if (nicknameCol != null) {
                sql.append(", c.").append(nicknameCol);
                colIndices.put("nickname", ++selectCount);
            }
            if (remarkCol != null) {
                sql.append(", c.").append(remarkCol);
                colIndices.put("remark", ++selectCount);
            }
            if (sexCol != null) {
                sql.append(", c.").append(sexCol);
                colIndices.put("sex", ++selectCount);
            }
            if (typeCol != null) {
                sql.append(", c.").append(typeCol);
                colIndices.put("type", ++selectCount);
            }

            boolean canJoinAvatar = (avatarTable != null && avatarUserCol != null && avatarUrlCol != null);
            if (canJoinAvatar) {
                sql.append(", a.").append(avatarUrlCol);
                colIndices.put("avatar", ++selectCount);
                sql.append(" FROM ").append(contactTableName).append(" c");
                sql.append(" LEFT JOIN ").append(avatarTable).append(" a ON c.")
                        .append(usernameCol).append(" = a.").append(avatarUserCol);
            } else {
                sql.append(" FROM ").append(contactTableName).append(" c");
            }

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql.toString())) {

                while (rs.next()) {
                    ContactInfo contact = new ContactInfo();
                    contact.username = rs.getString(1);

                    if (colIndices.containsKey("alias")) {
                        contact.alias = rs.getString(colIndices.get("alias"));
                    }
                    if (colIndices.containsKey("nickname")) {
                        contact.nickname = rs.getString(colIndices.get("nickname"));
                    }
                    if (colIndices.containsKey("remark")) {
                        contact.remark = rs.getString(colIndices.get("remark"));
                    }
                    if (colIndices.containsKey("sex")) {
                        contact.gender = rs.getInt(colIndices.get("sex"));
                    }
                    if (colIndices.containsKey("type")) {
                        contact.type = rs.getInt(colIndices.get("type"));
                    }
                    if (colIndices.containsKey("avatar")) {
                        contact.avatarUrl = rs.getString(colIndices.get("avatar"));
                    }

                    // 过滤数据：如果UserName为空则不添加，其他字段可以为空
                    if (contact.username != null && !contact.username.trim().isEmpty()) {
                        contacts.add(contact);
                    }
                }
            }
        }

        return contacts;
    }

    /**
     * 判断数据库是否包含指定表
     */
    private static boolean hasTable(Connection conn, String tableName) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, null, null)) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (tableName.equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 获取指定表的所有列名
     */
    private static Set<String> getTableColumns(Connection conn, String tableName) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, null, null)) {
            while (rs.next()) {
                String tName = rs.getString("TABLE_NAME");
                if (tableName.equalsIgnoreCase(tName)) {
                    columns.add(rs.getString("COLUMN_NAME"));
                }
            }
        }
        return columns;
    }

    /**
     * 在列名集合中寻找匹配的候选列名
     */
    private static String findColumn(Set<String> columns, String... candidates) {
        for (String cand : candidates) {
            for (String col : columns) {
                if (col.equalsIgnoreCase(cand)) {
                    return col;
                }
            }
        }
        return null;
    }

    /**
     * 批量下载选中联系人的头像到本地目录
     */
    public static void downloadAvatars(List<ContactInfo> selectedContacts, File destDir, ProgressCallback callback) {
        if (!destDir.exists()) {
            destDir.mkdirs();
        }

        int total = selectedContacts.size();
        for (int i = 0; i < total; i++) {
            ContactInfo contact = selectedContacts.get(i);
            
            if (contact.avatarUrl == null || contact.avatarUrl.trim().isEmpty()) {
                if (callback != null) {
                    callback.onProgress(i + 1, total, "跳过：" + contact.getDisplayName() + "（无头像链接）");
                }
                continue;
            }

            String displayName = contact.getDisplayName();
            String safeDisplayName = sanitizeFileName(displayName);
            if (safeDisplayName.isEmpty()) {
                safeDisplayName = "未命名";
            }

            // 附带微信号或wxid以便区分重名好友
            String identifier = "";
            if (contact.alias != null && !contact.alias.trim().isEmpty()) {
                identifier = "_" + sanitizeFileName(contact.alias.trim());
            } else if (contact.username != null && !contact.username.trim().isEmpty()) {
                identifier = "_" + sanitizeFileName(contact.username.trim());
            }

            String fileName = safeDisplayName + identifier + ".jpg";
            File file = new File(destDir, fileName);

            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(contact.avatarUrl.trim()).openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (InputStream in = conn.getInputStream();
                         FileOutputStream out = new FileOutputStream(file)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }
                    if (callback != null) {
                        callback.onProgress(i + 1, total, "下载成功：" + displayName);
                    }
                } else {
                    if (callback != null) {
                        callback.onProgress(i + 1, total, "下载失败：" + displayName + " (HTTP " + responseCode + ")");
                    }
                }
            } catch (Exception ex) {
                if (callback != null) {
                    callback.onProgress(i + 1, total, "下载异常：" + displayName + " (" + ex.getMessage() + ")");
                }
            }
        }
    }

    /**
     * 将联系人列表导出为 Excel 文件
     */
    public static void exportContactsToExcel(List<ContactInfo> contacts, File destFile) throws Exception {
        try (Workbook workbook = new XSSFWorkbook();
             FileOutputStream fos = new FileOutputStream(destFile)) {
            
            Sheet sheet = workbook.createSheet("微信通讯录");

            // 创建表头
            Row headerRow = sheet.createRow(0);
            String[] headers = {"昵称", "微信号", "备注名", "性别", "头像链接", "原始微信号(UserName)"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
            }

            // 写入数据
            int rowIdx = 1;
            for (ContactInfo contact : contacts) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(contact.nickname != null ? contact.nickname : "");
                row.createCell(1).setCellValue(contact.alias != null ? contact.alias : "");
                row.createCell(2).setCellValue(contact.remark != null ? contact.remark : "");

                String genderStr = "未知";
                if (contact.gender == 1) {
                    genderStr = "男";
                } else if (contact.gender == 2) {
                    genderStr = "女";
                }
                row.createCell(3).setCellValue(genderStr);
                
                row.createCell(4).setCellValue(contact.avatarUrl != null ? contact.avatarUrl : "");
                row.createCell(5).setCellValue(contact.username != null ? contact.username : "");
            }

            // 自适应列宽
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(fos);
        }
    }

    /**
     * 过滤文件名中的非法字符
     */
    public static String sanitizeFileName(String name) {
        if (name == null) return "";
        return name.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
    }

    /**
     * 从 Excel 中读取联系人列表
     */
    public static List<ContactInfo> readContactsFromExcel(File excelFile) throws Exception {
        List<ContactInfo> contacts = new ArrayList<>();
        try (InputStream is = new FileInputStream(excelFile);
             Workbook workbook = new XSSFWorkbook(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            int rowCount = sheet.getLastRowNum();
            if (rowCount < 0) return contacts;

            // 1. 尝试从第一行读取表头进行列对齐
            Row headerRow = sheet.getRow(0);
            int nickCol = 0, wechatCol = 1, remarkCol = 2, genderCol = 3;
            boolean hasHeader = false;
            if (headerRow != null) {
                for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                    Cell cell = headerRow.getCell(c);
                    if (cell != null) {
                        try {
                            String val = cell.getStringCellValue().trim();
                            if (val.contains("昵称") || val.equalsIgnoreCase("nickname")) { nickCol = c; hasHeader = true; }
                            else if (val.contains("微信") || val.equalsIgnoreCase("alias") || val.equalsIgnoreCase("wechat") || val.contains("账号") || val.contains("ID")) { wechatCol = c; hasHeader = true; }
                            else if (val.contains("备注") || val.equalsIgnoreCase("remark")) { remarkCol = c; hasHeader = true; }
                            else if (val.contains("性别") || val.equalsIgnoreCase("gender") || val.equalsIgnoreCase("sex")) { genderCol = c; hasHeader = true; }
                        } catch (Exception ignored) {}
                    }
                }
            }

            int startRow = hasHeader ? 1 : 0;
            for (int r = startRow; r <= rowCount; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                ContactInfo info = new ContactInfo();
                info.nickname = getRowCellValue(row, nickCol);
                info.alias = getRowCellValue(row, wechatCol);
                info.username = info.alias != null && !info.alias.isEmpty() ? info.alias : UUID.randomUUID().toString();
                info.remark = getRowCellValue(row, remarkCol);

                String genderVal = getRowCellValue(row, genderCol);
                if (genderVal.contains("男") || genderVal.equals("1")) info.gender = 1;
                else if (genderVal.contains("女") || genderVal.equals("2")) info.gender = 2;
                else info.gender = 0;

                info.type = 1;
                if (info.nickname.isEmpty() && info.alias.isEmpty() && info.remark.isEmpty()) continue;
                contacts.add(info);
            }
        }
        return contacts;
    }

    private static String getRowCellValue(Row row, int colIdx) {
        if (colIdx < 0 || colIdx >= row.getLastCellNum()) return "";
        Cell cell = row.getCell(colIdx);
        if (cell == null) return "";
        try {
            switch (cell.getCellType()) {
                case STRING: return cell.getStringCellValue().trim();
                case NUMERIC:
                    double d = cell.getNumericCellValue();
                    if (d == (long)d) return String.valueOf((long)d);
                    return String.valueOf(d);
                case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
                default: return "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 从 CSV / TXT 文件中读取联系人列表
     */
    public static List<ContactInfo> readContactsFromTextFile(File file) throws Exception {
        List<ContactInfo> contacts = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            boolean firstLine = true;
            int nickCol = 0, wechatCol = 1, remarkCol = 2, genderCol = 3;
            boolean hasHeader = false;
            String delimiter = null;

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                if (delimiter == null) {
                    if (line.contains("\t")) delimiter = "\t";
                    else if (line.contains(",")) delimiter = ",";
                    else if (line.contains(";")) delimiter = ";";
                    else delimiter = "\\s+";
                }

                String[] parts = line.split(delimiter);
                if (firstLine) {
                    firstLine = false;
                    boolean matchHeader = false;
                    for (int c = 0; c < parts.length; c++) {
                        String val = parts[c].trim();
                        if (val.contains("昵称") || val.equalsIgnoreCase("nickname")) { nickCol = c; matchHeader = true; }
                        else if (val.contains("微信") || val.equalsIgnoreCase("alias") || val.equalsIgnoreCase("wechat") || val.contains("账号") || val.contains("ID")) { wechatCol = c; matchHeader = true; }
                        else if (val.contains("备注") || val.equalsIgnoreCase("remark")) { remarkCol = c; matchHeader = true; }
                        else if (val.contains("性别") || val.equalsIgnoreCase("gender") || val.equalsIgnoreCase("sex")) { genderCol = c; matchHeader = true; }
                    }
                    if (matchHeader) {
                        hasHeader = true;
                        continue;
                    }
                }

                ContactInfo info = new ContactInfo();
                info.nickname = parts.length > nickCol ? parts[nickCol].trim() : "";
                info.alias = parts.length > wechatCol ? parts[wechatCol].trim() : "";
                info.username = info.alias != null && !info.alias.isEmpty() ? info.alias : UUID.randomUUID().toString();
                info.remark = parts.length > remarkCol ? parts[remarkCol].trim() : "";

                String genderVal = parts.length > genderCol ? parts[genderCol].trim() : "";
                if (genderVal.contains("男") || genderVal.equals("1")) info.gender = 1;
                else if (genderVal.contains("女") || genderVal.equals("2")) info.gender = 2;
                else info.gender = 0;

                info.type = 1;
                if (info.nickname.isEmpty() && info.alias.isEmpty() && info.remark.isEmpty()) continue;
                contacts.add(info);
            }
        }
        return contacts;
    }

    /**
     * 从剪贴板/纯文本中解析联系人列表
     */
    public static List<ContactInfo> parseContactsFromText(String text) {
        List<ContactInfo> contacts = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return contacts;
        String[] lines = text.split("\\r?\\n");
        String delimiter = null;
        boolean firstLine = true;
        int nickCol = 0, wechatCol = 1, remarkCol = 2, genderCol = 3;
        boolean hasHeader = false;

        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            if (delimiter == null) {
                if (line.contains("\t")) delimiter = "\t";
                else if (line.contains(",")) delimiter = ",";
                else if (line.contains(";")) delimiter = ";";
                else delimiter = "\\s+";
            }

            String[] parts = line.split(delimiter);
            if (firstLine) {
                firstLine = false;
                boolean matchHeader = false;
                for (int c = 0; c < parts.length; c++) {
                    String val = parts[c].trim();
                    if (val.contains("昵称") || val.equalsIgnoreCase("nickname")) { nickCol = c; matchHeader = true; }
                    else if (val.contains("微信") || val.equalsIgnoreCase("alias") || val.equalsIgnoreCase("wechat") || val.contains("账号") || val.contains("ID")) { wechatCol = c; matchHeader = true; }
                    else if (val.contains("备注") || val.equalsIgnoreCase("remark")) { remarkCol = c; matchHeader = true; }
                    else if (val.contains("性别") || val.equalsIgnoreCase("gender") || val.equalsIgnoreCase("sex")) { genderCol = c; matchHeader = true; }
                }
                if (matchHeader) {
                    hasHeader = true;
                    continue;
                }
            }

            ContactInfo info = new ContactInfo();
            info.nickname = parts.length > nickCol ? parts[nickCol].trim() : "";
            info.alias = parts.length > wechatCol ? parts[wechatCol].trim() : "";
            info.username = info.alias != null && !info.alias.isEmpty() ? info.alias : UUID.randomUUID().toString();
            info.remark = parts.length > remarkCol ? parts[remarkCol].trim() : "";

            String genderVal = parts.length > genderCol ? parts[genderCol].trim() : "";
            if (genderVal.contains("男") || genderVal.equals("1")) info.gender = 1;
            else if (genderVal.contains("女") || genderVal.equals("2")) info.gender = 2;
            else info.gender = 0;

            info.type = 1;
            if (info.nickname.isEmpty() && info.alias.isEmpty() && info.remark.isEmpty()) continue;
            contacts.add(info);
        }
        return contacts;
    }
}
