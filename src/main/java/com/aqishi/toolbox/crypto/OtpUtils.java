package com.aqishi.toolbox.crypto;

import org.apache.commons.codec.binary.Base32;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 谷歌验证器 (TOTP) 核心算法与工具类。
 * 遵循 RFC 6238 标准，提供 Base32 解码、TOTP 计算及 otpauth:// 链接解析逻辑。
 */
public final class OtpUtils {

    private OtpUtils() {
    }

    /**
     * 将 Base32 编码的密钥字符串解码为原始字节数组。
     * 自动去除空格、短横线并进行解码。
     */
    public static byte[] decodeBase32(String base32) {
        if (base32 == null) {
            return new byte[0];
        }
        String clean = base32.replace(" ", "").replace("-", "").toUpperCase();
        if (clean.isEmpty()) {
            return new byte[0];
        }
        if (!clean.matches("^[A-Z2-7=]+$")) {
            throw new IllegalArgumentException("密钥包含非法 Base32 字符");
        }
        Base32 codec = new Base32();
        return codec.decode(clean);
    }

    /**
     * 根据时间片计算 TOTP 动态验证码。
     *
     * @param key       解码后的原始密钥字节数组
     * @param timeState 当前时间片（System.currentTimeMillis() / 1000 / period）
     * @param digits    验证码位数（支持 6 位或 8 位）
     * @param algorithm 内部算法名（如 "HmacSHA1", "HmacSHA256", "HmacSHA512"）
     * @return 格式化后的验证码（包含前导 0）
     */
    public static String generateTOTP(byte[] key, long timeState, int digits, String algorithm) throws Exception {
        byte[] data = new byte[8];
        long value = timeState;
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (value & 0xFF);
            value >>= 8;
        }

        SecretKeySpec signKey = new SecretKeySpec(key, algorithm);
        Mac mac = Mac.getInstance(algorithm);
        mac.init(signKey);
        byte[] hash = mac.doFinal(data);

        int offset = hash[hash.length - 1] & 0xF;
        int binary = ((hash[offset] & 0x7F) << 24) |
                     ((hash[offset + 1] & 0xFF) << 16) |
                     ((hash[offset + 2] & 0xFF) << 8) |
                     (hash[offset + 3] & 0xFF);

        int pinValue = binary % (int) Math.pow(10, digits);
        return String.format("%0" + digits + "d", pinValue);
    }

    /**
     * 解析标准的 otpauth:// 链接，提取参数。
     * 格式示例：otpauth://totp/Issuer:user@example.com?secret=xxxx&issuer=Issuer&period=30
     */
    public static OtpConfig parseOtpAuthUrl(String url) throws Exception {
        if (url == null || !url.toLowerCase().startsWith("otpauth://")) {
            throw new IllegalArgumentException("链接格式不正确，必须以 otpauth:// 开头");
        }

        // 解析 URL
        java.net.URI uri = new java.net.URI(url);
        String host = uri.getHost();
        if (host == null || !host.equalsIgnoreCase("totp")) {
            throw new IllegalArgumentException("暂不支持除 totp 以外的其它 OTP 类型 (如: " + host + ")");
        }

        OtpConfig config = new OtpConfig();

        // 解析 Path 提取 label
        String path = uri.getPath();
        if (path != null && path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path != null && !path.isEmpty()) {
            String decodedLabel = URLDecoder.decode(path, StandardCharsets.UTF_8.name());
            config.label = decodedLabel;
            int colonIndex = decodedLabel.indexOf(':');
            if (colonIndex > 0) {
                config.issuer = decodedLabel.substring(0, colonIndex).trim();
            }
        }

        // 解析 Query 参数
        String query = uri.getRawQuery();
        if (query != null && !query.isEmpty()) {
            Map<String, String> queryParams = parseQuery(query);
            
            String secret = queryParams.get("secret");
            if (secret == null || secret.isEmpty()) {
                throw new IllegalArgumentException("链接中未找到必填参数: secret");
            }
            config.secret = secret.toUpperCase();

            String queryIssuer = queryParams.get("issuer");
            if (queryIssuer != null && !queryIssuer.isEmpty()) {
                config.issuer = URLDecoder.decode(queryIssuer, StandardCharsets.UTF_8.name());
            }

            // 若 label 缺省，则用 issuer 兜底
            if (config.label == null || config.label.isEmpty()) {
                config.label = config.issuer != null ? config.issuer : "未命名账户";
            }

            String algorithm = queryParams.get("algorithm");
            if (algorithm != null && !algorithm.isEmpty()) {
                // 转换算法标准名称 (如 sha1 -> SHA1, hmac-sha256 -> SHA256)
                String algClean = algorithm.toUpperCase().replace("HMAC-", "").replace("HMAC", "");
                if (algClean.equals("SHA1") || algClean.equals("SHA256") || algClean.equals("SHA512")) {
                    config.algorithm = algClean;
                }
            }

            String digits = queryParams.get("digits");
            if (digits != null && !digits.isEmpty()) {
                try {
                    int d = Integer.parseInt(digits);
                    if (d == 6 || d == 8) {
                        config.digits = d;
                    }
                } catch (NumberFormatException ignored) {}
            }

            String period = queryParams.get("period");
            if (period != null && !period.isEmpty()) {
                try {
                    int p = Integer.parseInt(period);
                    if (p > 0) {
                        config.period = p;
                    }
                } catch (NumberFormatException ignored) {}
            }
        } else {
            throw new IllegalArgumentException("链接未携带任何配置参数");
        }

        return config;
    }

    private static Map<String, String> parseQuery(String query) throws Exception {
        Map<String, String> map = new HashMap<>();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                String key = pair.substring(0, idx).toLowerCase();
                String value = pair.substring(idx + 1);
                map.put(key, value);
            }
        }
        return map;
    }

    /**
     * 对应 OTP 配置项结构体。
     */
    public static class OtpConfig {
        public String secret;
        public String label;
        public String issuer;
        public String algorithm = "SHA1"; // 默认 SHA1
        public int digits = 6;            // 默认 6 位
        public int period = 30;           // 默认 30 秒步长

        @Override
        public String toString() {
            return "OtpConfig{" +
                    "secret='***'" +
                    ", label='" + label + '\'' +
                    ", issuer='" + issuer + '\'' +
                    ", algorithm='" + algorithm + '\'' +
                    ", digits=" + digits +
                    ", period=" + period +
                    '}';
        }
    }
}
