package com.aqishi.toolbox.feature.system.domain;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 各平台「端口 → 进程」命令输出的纯解析器。
 *
 * <p>所有方法都只按<em>结构</em>识别数据行，不认表头文字：中文 Windows 的 netstat 表头是
 * 「协议 本地地址 外部地址 状态 PID」，德文版又是另一套，按表头定位列在本地化系统上必然失效。
 * 数据行的第一列却总是 TCP/UDP、地址总是 {@code 主机:端口}、PID 总是末尾的数字——
 * 认这些不变量即可。解析不了的行一律跳过，不抛异常：命令输出里夹杂的提示行、截断行
 * 不应让整张表作废。</p>
 */
public final class PortTableParser {

    /** ss 的进程段：{@code ("java",pid=1234,fd=56)}；进程名里的引号会被 ss 转义。 */
    private static final Pattern SS_USER = Pattern.compile("\\(\"((?:[^\"\\\\]|\\\\.)*)\",pid=(\\d+)");
    /** Linux netstat 的 {@code PID/Program name} 列。 */
    private static final Pattern NETSTAT_PID = Pattern.compile("^(\\d+)/(.*)$");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z_\\-0-9]*");

    /**
     * 各平台、各语言对同一状态的不同写法，键已做过大写、连字符与空格换下划线的归一。
     * 中文 Windows 的 netstat 状态列本身就是英文，无需映射；德、西、意、葡等语言版会翻译状态名。
     */
    private static final Map<String, String> STATE_ALIASES = new HashMap<>();

    static {
        alias("LISTEN", "LISTENING", "ABH\u00d6REN", "ABHOEREN", "ESCUCHANDO", "IN_ASCOLTO",
                "ESCUTANDO", "\u00c9COUTE", "ECOUTE");
        alias("ESTABLISHED", "ESTAB", "HERGESTELLT", "ESTABLECIDO", "STABILITA", "STABILITO",
                "ESTABELECIDA", "\u00c9TABLI", "ETABLI");
        alias("TIME_WAIT", "WARTEND");
        alias("CLOSE_WAIT", "SCHLIESSEN_WARTEN");
        alias("SYN_SENT", "SYN_GESENDET");
        alias("SYN_RECEIVED", "SYN_RECV", "SYN_EMPFANGEN");
        // ss 用 UNCONN 表示未连接的 UDP；Windows、lsof 对此都留空，统一成空串。
        alias("", "UNCONN");
    }

    private PortTableParser() {
    }

    private static void alias(String canonical, String... variants) {
        for (String variant : variants) {
            STATE_ALIASES.put(variant, canonical);
        }
    }

    // ==========================================
    // Windows
    // ==========================================

    /**
     * 解析 Windows {@code netstat -ano}。
     *
     * <p>TCP 行：{@code 协议 本地 远端 状态 PID}；UDP 行没有状态列：{@code UDP 0.0.0.0:123 *:* 1234}。
     * 状态可能被翻译成带空格的词组（意大利语 {@code IN ASCOLTO}），所以取「第四列到倒数第二列」。</p>
     */
    public static List<PortEntry> parseWindowsNetstat(String text) {
        List<PortEntry> entries = new ArrayList<>();
        for (String line : lines(text)) {
            String[] tokens = tokens(line);
            if (tokens.length < 3) {
                continue;
            }
            PortEntry.Protocol protocol = protocolOf(tokens[0]);
            if (protocol == null) {
                continue;
            }
            Endpoint local = parseEndpoint(tokens[1]);
            Endpoint remote = parseEndpoint(tokens[2]);
            if (local == null || remote == null) {
                continue;
            }
            int end = tokens.length;
            long pid = PortEntry.NO_PID;
            if (end > 3 && DIGITS.matcher(tokens[end - 1]).matches()) {
                pid = parsePid(tokens[end - 1]);
                end--;
            }
            String state = normalizeState(join(tokens, 3, end));
            entries.add(new PortEntry(protocol, local.address(), local.port(), remote.address(),
                    remote.port(), state, pid, "", ""));
        }
        return entries;
    }

    /**
     * 解析 {@code tasklist /FO CSV /NH}，得到 PID → 映像名。
     *
     * <p>每个字段都带引号，内存列是本地化数字（{@code "12,345 K"}、{@code "12.345 K"}），
     * 其中的逗号在引号内，必须用真正的 CSV 规则切分而不是按逗号 split。</p>
     */
    public static Map<Long, String> parseTasklistCsv(String text) {
        Map<Long, String> names = new LinkedHashMap<>();
        for (String line : lines(text)) {
            if (!line.startsWith("\"")) {
                continue;
            }
            List<String> fields = parseCsvLine(line);
            if (fields.size() < 2 || !DIGITS.matcher(fields.get(1).trim()).matches()) {
                continue;
            }
            long pid = parsePid(fields.get(1).trim());
            if (pid >= 0) {
                names.putIfAbsent(pid, fields.get(0));
            }
        }
        return names;
    }

    // ==========================================
    // lsof
    // ==========================================

    /**
     * 解析 {@code lsof -nP -iTCP -iUDP}（含 {@code -sTCP:LISTEN} 变体，macOS 与 Linux 通用）。
     *
     * <p>列：{@code COMMAND PID USER FD TYPE DEVICE SIZE/OFF NODE NAME}。SIZE/OFF 在某些条目上
     * 可能为空，列数不固定，所以不按下标取 NAME，而是找到 NODE 列（TCP/UDP）后把其余部分当 NAME。
     * 同一套接字被多个 fd 或 fork 出的子进程共享时 lsof 会列多行，按「端点 + PID + 状态」去重。</p>
     */
    public static List<PortEntry> parseLsof(String text) {
        Map<String, PortEntry> unique = new LinkedHashMap<>();
        for (String line : lines(text)) {
            String[] tokens = tokens(line);
            if (tokens.length < 5 || "COMMAND".equals(tokens[0])
                    || !DIGITS.matcher(tokens[1]).matches()) {
                continue;
            }
            int node = -1;
            for (int i = 3; i < tokens.length - 1; i++) {
                if (protocolOf(tokens[i]) != null && tokens[i + 1].indexOf(':') >= 0) {
                    node = i;
                    break;
                }
            }
            if (node < 0) {
                continue;
            }
            PortEntry.Protocol protocol = protocolOf(tokens[node]);
            String name = join(tokens, node + 1, tokens.length);
            String state = "";
            int paren = name.lastIndexOf(" (");
            if (paren > 0 && name.endsWith(")")) {
                state = normalizeState(name.substring(paren + 2, name.length() - 1));
                name = name.substring(0, paren).trim();
            }
            String localPart = name;
            String remotePart = null;
            int arrow = name.indexOf("->");
            if (arrow >= 0) {
                localPart = name.substring(0, arrow);
                remotePart = name.substring(arrow + 2);
            }
            Endpoint local = parseEndpoint(localPart);
            if (local == null) {
                continue;
            }
            Endpoint remote = remotePart == null ? new Endpoint("*", PortEntry.NO_PORT) : parseEndpoint(remotePart);
            if (remote == null) {
                continue;
            }
            PortEntry entry = new PortEntry(protocol, local.address(), local.port(), remote.address(),
                    remote.port(), state, parsePid(tokens[1]), unescapeLsof(tokens[0]), "");
            unique.putIfAbsent(entry.identity() + "|" + state, entry);
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * 还原 lsof 对 COMMAND 的转义：空格等不可打印字符写成 {@code \x20}，多字节 UTF-8 逐字节转义。
     */
    public static String unescapeLsof(String value) {
        if (value == null || value.indexOf('\\') < 0) {
            return value == null ? "" : value;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                if (next == 'x' && i + 3 < value.length() && isHex(value.charAt(i + 2)) && isHex(value.charAt(i + 3))) {
                    bytes.write(Integer.parseInt(value.substring(i + 2, i + 4), 16));
                    i += 4;
                    continue;
                }
                int simple = switch (next) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case '\\' -> '\\';
                    default -> -1;
                };
                if (simple >= 0) {
                    bytes.write(simple);
                    i += 2;
                    continue;
                }
            }
            byte[] encoded = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            bytes.write(encoded, 0, encoded.length);
            i++;
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    // ==========================================
    // ss / Linux netstat
    // ==========================================

    /**
     * 解析 Linux {@code ss -tuanp}、{@code ss -tulpnH} 等输出。
     *
     * <p>同时给了 {@code -t -u} 时每行以 Netid（tcp/udp）开头；只给 {@code -t} 时没有 Netid 列，
     * 首列就是状态，此时用 {@code defaultProtocol} 补上。一个套接字可能被多个进程共享
     * （nginx 的 master 与 worker），{@code users:(...)} 里每个不同的 PID 各成一行；
     * 非 root 运行时别人的套接字没有 users 段，PID 记为未知。</p>
     *
     * @param defaultProtocol 没有 Netid 列时采用的协议，可为 null（此时跳过无 Netid 的行）
     */
    public static List<PortEntry> parseSs(String text, PortEntry.Protocol defaultProtocol) {
        List<PortEntry> entries = new ArrayList<>();
        for (String line : lines(text)) {
            String[] tokens = tokens(line);
            if (tokens.length < 5 || "Netid".equalsIgnoreCase(tokens[0]) || "State".equalsIgnoreCase(tokens[0])) {
                continue;
            }
            PortEntry.Protocol protocol = protocolOf(tokens[0]);
            int index;
            if (protocol != null) {
                index = 1;
            } else if (defaultProtocol != null && WORD.matcher(tokens[0]).matches()) {
                protocol = defaultProtocol;
                index = 0;
            } else {
                continue;
            }
            if (tokens.length < index + 5) {
                continue;
            }
            Endpoint local = parseEndpoint(tokens[index + 3]);
            Endpoint remote = parseEndpoint(tokens[index + 4]);
            if (local == null || remote == null) {
                continue;
            }
            String state = normalizeState(tokens[index]);
            Map<Long, String> users = parseSsUsers(join(tokens, index + 5, tokens.length));
            if (users.isEmpty()) {
                entries.add(new PortEntry(protocol, local.address(), local.port(), remote.address(),
                        remote.port(), state, PortEntry.NO_PID, "", ""));
                continue;
            }
            for (Map.Entry<Long, String> user : users.entrySet()) {
                entries.add(new PortEntry(protocol, local.address(), local.port(), remote.address(),
                        remote.port(), state, user.getKey(), user.getValue(), ""));
            }
        }
        return entries;
    }

    /** 从 {@code users:(("a",pid=1,fd=3),("b",pid=2,fd=4))} 中取出去重后的 PID → 进程名。 */
    static Map<Long, String> parseSsUsers(String text) {
        Map<Long, String> users = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) {
            return users;
        }
        Matcher matcher = SS_USER.matcher(text);
        while (matcher.find()) {
            long pid = parsePid(matcher.group(2));
            if (pid >= 0) {
                users.putIfAbsent(pid, matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
            }
        }
        return users;
    }

    /**
     * 解析 Linux {@code netstat -tulpn} / {@code netstat -tuanp}（net-tools）。
     *
     * <p>最后的 {@code PID/Program name} 列在没有权限时是 {@code -}；程序名可能含空格
     * （{@code 4242/Web Content}），所以从第一个 {@code 数字/} 开头的词起全部归入该列。</p>
     */
    public static List<PortEntry> parseLinuxNetstat(String text) {
        List<PortEntry> entries = new ArrayList<>();
        for (String line : lines(text)) {
            String[] tokens = tokens(line);
            if (tokens.length < 5) {
                continue;
            }
            PortEntry.Protocol protocol = protocolOf(tokens[0]);
            if (protocol == null) {
                continue;
            }
            Endpoint local = parseEndpoint(tokens[3]);
            Endpoint remote = parseEndpoint(tokens[4]);
            if (local == null || remote == null) {
                continue;
            }
            int processStart = tokens.length;
            for (int i = 5; i < tokens.length; i++) {
                if (NETSTAT_PID.matcher(tokens[i]).matches() || ("-".equals(tokens[i]) && i == tokens.length - 1)) {
                    processStart = i;
                    break;
                }
            }
            long pid = PortEntry.NO_PID;
            String name = "";
            if (processStart < tokens.length) {
                Matcher matcher = NETSTAT_PID.matcher(join(tokens, processStart, tokens.length));
                if (matcher.matches()) {
                    pid = parsePid(matcher.group(1));
                    name = matcher.group(2).trim();
                }
            }
            String state = normalizeState(join(tokens, 5, processStart));
            entries.add(new PortEntry(protocol, local.address(), local.port(), remote.address(),
                    remote.port(), state, pid, name, ""));
        }
        return entries;
    }

    // ==========================================
    // 命令行
    // ==========================================

    /**
     * 整理 PowerShell / wmic / ps 查到的命令行。
     *
     * <p>wmic 的输出第一行是列名 {@code CommandLine}，行尾还有大量补齐空格；PowerShell 与 ps
     * 只输出值本身。返回空串表示没拿到（进程已退出，或无权读取高权限进程）。</p>
     */
    public static String parseCommandLine(String text) {
        List<String> parts = new ArrayList<>();
        boolean first = true;
        for (String line : lines(text)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (first && "CommandLine".equalsIgnoreCase(trimmed)) {
                first = false;
                continue;
            }
            first = false;
            parts.add(trimmed);
        }
        return String.join(" ", parts);
    }

    /** Linux {@code /proc/<pid>/cmdline} 以 NUL 分隔参数，末尾通常还有一个 NUL。 */
    public static String parseNulSeparated(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String part : text.split("\u0000")) {
            if (!part.isEmpty()) {
                parts.add(part.indexOf(' ') >= 0 ? "\"" + part + "\"" : part);
            }
        }
        return String.join(" ", parts);
    }

    // ==========================================
    // 组合
    // ==========================================

    /** 给没有进程名的记录补上 tasklist 查到的映像名。 */
    public static List<PortEntry> attachProcessNames(List<PortEntry> entries, Map<Long, String> names) {
        if (names == null || names.isEmpty()) {
            return entries;
        }
        List<PortEntry> result = new ArrayList<>(entries.size());
        for (PortEntry entry : entries) {
            String name = entry.hasPid() && entry.processName().isEmpty() ? names.get(entry.pid()) : null;
            result.add(name == null ? entry : entry.withProcessName(name));
        }
        return result;
    }

    /** 去掉完全相同的记录（同一套接字被同一进程的多个 fd 持有）。 */
    public static List<PortEntry> distinct(List<PortEntry> entries) {
        Set<PortEntry> seen = new LinkedHashSet<>(entries);
        return new ArrayList<>(seen);
    }

    // ==========================================
    // 基础
    // ==========================================

    /** 地址与端口，端口为通配或未知时是 {@link PortEntry#NO_PORT}。 */
    record Endpoint(String address, int port) {
    }

    /**
     * 解析 {@code 主机:端口}。支持 {@code *:*}、{@code 0.0.0.0:135}、{@code [::]:135}、
     * {@code [fe80::1%12]:5353}、ss 的 {@code [fe80::1]%eth0:546} 与 {@code 127.0.0.53%lo:53}、
     * net-tools 不带方括号的 {@code :::22}。无法识别时返回 null。
     */
    static Endpoint parseEndpoint(String token) {
        if (token == null) {
            return null;
        }
        String value = token.trim();
        if (value.isEmpty()) {
            return null;
        }
        String address;
        String port;
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) {
                return null;
            }
            address = value.substring(1, close);
            String rest = value.substring(close + 1);
            int colon = rest.lastIndexOf(':');
            if (colon < 0) {
                return null;
            }
            // ss 把网卡写在方括号外：[fe80::1]%eth0:546。
            if (rest.startsWith("%")) {
                address = address + rest.substring(0, colon);
            }
            port = rest.substring(colon + 1);
        } else {
            int colon = value.lastIndexOf(':');
            if (colon < 0) {
                return null;
            }
            address = value.substring(0, colon);
            port = value.substring(colon + 1);
        }
        if (address.isEmpty()) {
            address = "*";
        }
        if ("*".equals(port)) {
            return new Endpoint(address, PortEntry.NO_PORT);
        }
        if (!DIGITS.matcher(port).matches() || port.length() > 5) {
            return null;
        }
        int number = Integer.parseInt(port);
        return number > 65535 ? null : new Endpoint(address, number);
    }

    /** 把各平台、各语言的状态名换算成统一的大写形式；认不出的原样大写返回。 */
    public static String normalizeState(String raw) {
        if (raw == null) {
            return "";
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (key.isEmpty()) {
            return "";
        }
        String canonical = STATE_ALIASES.get(key);
        return canonical != null ? canonical : key;
    }

    /** 按 RFC 4180 切分一行 CSV：字段可带引号，引号内的 {@code ""} 表示一个引号。 */
    static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private static PortEntry.Protocol protocolOf(String token) {
        String value = token.toUpperCase(Locale.ROOT);
        return switch (value) {
            case "TCP", "TCP6", "TCP4" -> PortEntry.Protocol.TCP;
            case "UDP", "UDP6", "UDP4" -> PortEntry.Protocol.UDP;
            default -> null;
        };
    }

    private static long parsePid(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return PortEntry.NO_PID;
        }
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

    private static String join(String[] tokens, int from, int to) {
        if (from >= to) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(tokens[i]);
        }
        return text.toString();
    }

    private static String[] tokens(String line) {
        String trimmed = line.trim();
        return trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
    }

    private static List<String> lines(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        String value = text.startsWith("\uFEFF") ? text.substring(1) : text;
        return List.of(value.split("\\r?\\n|\\r"));
    }
}
