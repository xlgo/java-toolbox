package com.aqishi.toolbox.feature.security.domain;

import org.bouncycastle.crypto.digests.SM3Digest;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.*;

/**
 * 文件批量摘要计算、清单比对与数字签名校验服务。
 */
public class BatchDigestService {

    public static final String[] SUPPORTED_ALGORITHMS = {"MD5", "SHA-1", "SHA-256", "SHA-512", "SM3"};
    private static final int BUFFER_SIZE = 64 * 1024; // 64KB 缓冲区

    /**
     * 单个文件的摘要计算结果。
     */
    public static class DigestResult {
        private final File file;
        private final String fileName;
        private final long fileSize;
        private final Map<String, String> hashes = new LinkedHashMap<>();
        private long durationMillis;
        private boolean success;
        private String errorMessage;
        private String matchStatus = "-"; // "PASS", "MISMATCH", "NOT_FOUND", "-"

        public DigestResult(File file) {
            this.file = file;
            this.fileName = file != null ? file.getName() : "";
            this.fileSize = file != null && file.exists() ? file.length() : 0;
        }

        public File getFile() { return file; }
        public String getFileName() { return fileName; }
        public long getFileSize() { return fileSize; }
        public Map<String, String> getHashes() { return hashes; }
        public long getDurationMillis() { return durationMillis; }
        public void setDurationMillis(long durationMillis) { this.durationMillis = durationMillis; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public String getMatchStatus() { return matchStatus; }
        public void setMatchStatus(String matchStatus) { this.matchStatus = matchStatus; }
    }

    /**
     * 计算单个文件的多种哈希摘要（单次 I/O 流式处理，极低内存消耗）。
     */
    public DigestResult computeFileDigest(File file, List<String> algorithms) {
        DigestResult result = new DigestResult(file);
        if (file == null || !file.exists() || !file.isFile()) {
            result.setSuccess(false);
            result.setErrorMessage("文件不存在或不是普通文件");
            return result;
        }

        long start = System.currentTimeMillis();
        try {
            // 初始化选中的计算器
            Map<String, MessageDigest> mdMap = new LinkedHashMap<>();
            SM3Digest sm3 = null;

            for (String alg : algorithms) {
                if ("SM3".equalsIgnoreCase(alg)) {
                    sm3 = new SM3Digest();
                } else {
                    mdMap.put(alg, MessageDigest.getInstance(alg));
                }
            }

            byte[] buffer = new byte[BUFFER_SIZE];
            try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
                int read;
                while ((read = is.read(buffer)) != -1) {
                    for (MessageDigest md : mdMap.values()) {
                        md.update(buffer, 0, read);
                    }
                    if (sm3 != null) {
                        sm3.update(buffer, 0, read);
                    }
                }
            }

            for (Map.Entry<String, MessageDigest> entry : mdMap.entrySet()) {
                byte[] digestBytes = entry.getValue().digest();
                result.getHashes().put(entry.getKey(), bytesToHex(digestBytes));
            }
            if (sm3 != null) {
                byte[] sm3Bytes = new byte[sm3.getDigestSize()];
                sm3.doFinal(sm3Bytes, 0);
                result.getHashes().put("SM3", bytesToHex(sm3Bytes));
            }

            result.setSuccess(true);
        } catch (Exception ex) {
            result.setSuccess(false);
            result.setErrorMessage(ex.getMessage());
        } finally {
            result.setDurationMillis(System.currentTimeMillis() - start);
        }
        return result;
    }

    /**
     * 递归扫描目录下的所有文件。
     */
    public List<File> scanFiles(File dirOrFile, boolean recursive) {
        List<File> list = new ArrayList<>();
        if (dirOrFile == null || !dirOrFile.exists()) return list;
        if (dirOrFile.isFile()) {
            list.add(dirOrFile);
            return list;
        }
        File[] children = dirOrFile.listFiles();
        if (children != null) {
            for (File c : children) {
                if (c.isFile()) {
                    list.add(c);
                } else if (c.isDirectory() && recursive) {
                    list.addAll(scanFiles(c, true));
                }
            }
        }
        return list;
    }

    /**
     * 解析并比对标准哈希校验清单（格式：<hash> [* ]<filename>）。
     */
    public Map<String, String> parseChecksumFile(String content) {
        Map<String, String> map = new LinkedHashMap<>();
        if (content == null || content.trim().isEmpty()) return map;

        String[] lines = content.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            // 典型格式: "2c7a... *filename.ext" 或 "2c7a...  filename.ext"
            int spaceIdx = trimmed.indexOf(' ');
            if (spaceIdx > 0) {
                String hash = trimmed.substring(0, spaceIdx).trim();
                String name = trimmed.substring(spaceIdx).trim();
                if (name.startsWith("*")) name = name.substring(1).trim();
                if (!hash.isEmpty() && !name.isEmpty()) {
                    map.put(name, hash.toLowerCase(Locale.ROOT));
                }
            }
        }
        return map;
    }

    /**
     * 将当前结果与校验清单比对，更新 MatchStatus。
     */
    public void matchChecksums(List<DigestResult> results, Map<String, String> checksumMap, String targetAlgorithm) {
        if (results == null || checksumMap == null) return;
        for (DigestResult r : results) {
            String expected = checksumMap.get(r.getFileName());
            if (expected == null) {
                // 也尝试比对绝对路径或基名
                for (Map.Entry<String, String> e : checksumMap.entrySet()) {
                    if (r.getFile().getAbsolutePath().endsWith(e.getKey()) || r.getFileName().equals(new File(e.getKey()).getName())) {
                        expected = e.getValue();
                        break;
                    }
                }
            }
            if (expected == null) {
                r.setMatchStatus("-");
                continue;
            }
            String actual = r.getHashes().get(targetAlgorithm);
            if (actual == null) {
                r.setMatchStatus("-");
            } else if (actual.equalsIgnoreCase(expected)) {
                r.setMatchStatus("PASS");
            } else {
                r.setMatchStatus("FAIL");
            }
        }
    }

    /**
     * 导出标准格式的校验清单。
     */
    public String exportChecksums(List<DigestResult> results, String algorithm) {
        StringBuilder sb = new StringBuilder();
        for (DigestResult r : results) {
            if (r.isSuccess()) {
                String hash = r.getHashes().get(algorithm);
                if (hash != null) {
                    sb.append(hash).append("  ").append(r.getFileName()).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 对文件流进行数字签名（例如 SHA256withRSA）。
     */
    public byte[] signFile(File file, PrivateKey privateKey, String signatureAlgorithm) throws Exception {
        Signature sig = Signature.getInstance(signatureAlgorithm);
        sig.initSign(privateKey);
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
            int read;
            while ((read = is.read(buffer)) != -1) {
                sig.update(buffer, 0, read);
            }
        }
        return sig.sign();
    }

    /**
     * 校验文件的数字签名。
     */
    public boolean verifyFileSignature(File file, PublicKey publicKey, byte[] signatureBytes, String signatureAlgorithm) throws Exception {
        Signature sig = Signature.getInstance(signatureAlgorithm);
        sig.initVerify(publicKey);
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
            int read;
            while ((read = is.read(buffer)) != -1) {
                sig.update(buffer, 0, read);
            }
        }
        return sig.verify(signatureBytes);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
