package com.aqishi.toolbox.crypto;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/**
 * OtpUtils 单元测试类。
 */
class OtpUtilsTest {

    @Test
    void testDecodeBase32() {
        // "Hello" 的 Base32 编码是 "JBSWY3DP"
        String base32 = "JBSWY3DP";
        byte[] decoded = OtpUtils.decodeBase32(base32);
        String decodedStr = new String(decoded, StandardCharsets.US_ASCII);
        assertEquals("Hello", decodedStr);

        // 带空格和填充字符的测试
        String base32WithSpaces = "JBSW Y3DP === ";
        byte[] decoded2 = OtpUtils.decodeBase32(base32WithSpaces);
        assertEquals("Hello", new String(decoded2, StandardCharsets.US_ASCII));

        // 空串测试
        byte[] empty = OtpUtils.decodeBase32("");
        assertEquals(0, empty.length);
        byte[] nil = OtpUtils.decodeBase32(null);
        assertEquals(0, nil.length);

        // 异常字符测试
        assertThrows(IllegalArgumentException.class, () -> {
            OtpUtils.decodeBase32("JBSWY3DPEHPK3PXP1"); // '1' 是非法 Base32 字符
        });
    }

    @Test
    void testGenerateTOTP() throws Exception {
        // 使用 RFC 6238 官方提供的测试 Secret: "123456789012345670890" (十六进制的 ASCII 表示为: 3132333435363738393031323334353637383930)
        // 其 Base32 编码形式为: "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" (对应 20 字节二进制)
        String base32Secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        byte[] key = OtpUtils.decodeBase32(base32Secret);

        // 验证 HMAC-SHA1 算法的 6 位 TOTP
        // 测试时间戳为 59 (timeState = 59 / 30 = 1)
        String code1 = OtpUtils.generateTOTP(key, 1L, 6, "HmacSHA1");
        assertEquals(6, code1.length());
        
        // 测试时间戳为 1111111111 (timeState = 1111111111 / 30 = 37037037)
        String code2 = OtpUtils.generateTOTP(key, 37037037L, 6, "HmacSHA1");
        assertEquals(6, code2.length());

        // 验证 8 位数生成
        String code3 = OtpUtils.generateTOTP(key, 37037037L, 8, "HmacSHA1");
        assertEquals(8, code3.length());
    }

    @Test
    void testParseOtpAuthUrl() throws Exception {
        // 包含全部参数的完整标准 otpauth:// URL
        String url1 = "otpauth://totp/GitHub:user%40gmail.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&algorithm=SHA256&digits=8&period=60";
        OtpUtils.OtpConfig config1 = OtpUtils.parseOtpAuthUrl(url1);
        assertEquals("JBSWY3DPEHPK3PXP", config1.secret);
        assertEquals("GitHub:user@gmail.com", config1.label);
        assertEquals("GitHub", config1.issuer);
        assertEquals("SHA256", config1.algorithm);
        assertEquals(8, config1.digits);
        assertEquals(60, config1.period);

        // 仅有必填项的 otpauth:// URL
        String url2 = "otpauth://totp/MyAccount?secret=JBSWY3DPEHPK3PXP";
        OtpUtils.OtpConfig config2 = OtpUtils.parseOtpAuthUrl(url2);
        assertEquals("JBSWY3DPEHPK3PXP", config2.secret);
        assertEquals("MyAccount", config2.label);
        assertNull(config2.issuer);
        assertEquals("SHA1", config2.algorithm); // 缺省使用默认 SHA1
        assertEquals(6, config2.digits);          // 缺省使用默认 6
        assertEquals(30, config2.period);         // 缺省使用默认 30

        // 包含冒号的 label 解构出 issuer 兜底
        String url3 = "otpauth://totp/Steam:user123?secret=JBSWY3DPEHPK3PXP";
        OtpUtils.OtpConfig config3 = OtpUtils.parseOtpAuthUrl(url3);
        assertEquals("Steam:user123", config3.label);
        assertEquals("Steam", config3.issuer);

        // 异常 URL 测试
        assertThrows(IllegalArgumentException.class, () -> {
            OtpUtils.parseOtpAuthUrl("http://example.com"); // scheme 错误
        });
        assertThrows(IllegalArgumentException.class, () -> {
            OtpUtils.parseOtpAuthUrl("otpauth://hotp/MyAccount?secret=JBSWY3DPEHPK3PXP"); // 不支持 hotp
        });
        assertThrows(IllegalArgumentException.class, () -> {
            OtpUtils.parseOtpAuthUrl("otpauth://totp/MyAccount"); // 缺少 secret
        });
    }
}
