package com.aqishi.toolbox.feature.security.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TOTP / Base32 / otpauth:// 解析测试。
 */
class OtpUtilsTest {

    @Test
    void decodeBase32RoundTrip() {
        String secret = "JBSWY3DPEHPK3PXP";
        byte[] decoded = OtpUtils.decodeBase32(secret);
        // 解码后再编码（去填充）应与原串一致
        String reEncoded = new org.apache.commons.codec.binary.Base32()
                .encodeAsString(decoded).replace("=", "").trim();
        assertEquals(secret, reEncoded);
        // 大小写不敏感
        assertArrayEquals(decoded, OtpUtils.decodeBase32(secret.toLowerCase()));
    }

    @Test
    void decodeBase32EmptyYieldsEmptyArray() {
        assertArrayEquals(new byte[0], OtpUtils.decodeBase32(null));
        assertArrayEquals(new byte[0], OtpUtils.decodeBase32(""));
    }

    @Test
    void decodeBase32ToleratesWhitespaceAndPadding() {
        byte[] a = OtpUtils.decodeBase32("JBSW Y3DP EHPK 3PXP");
        byte[] b = OtpUtils.decodeBase32("JBSWY3DPEHPK3PXP");
        assertArrayEquals(a, b);
    }

    @Test
    void decodeBase32RejectsIllegalChars() {
        assertThrows(IllegalArgumentException.class, () -> OtpUtils.decodeBase32("JBSWY3DP!@#$"));
    }

    @Test
    void generateTotpIsDeterministicForSameTimeState() throws Exception {
        byte[] key = OtpUtils.decodeBase32("JBSWY3DPEHPK3PXP");
        String a = OtpUtils.generateTOTP(key, 123456L, 6, "HmacSHA1");
        String b = OtpUtils.generateTOTP(key, 123456L, 6, "HmacSHA1");
        assertEquals(a, b);
        assertEquals(6, a.length());
    }

    @Test
    void generateTotpEightDigits() throws Exception {
        byte[] key = OtpUtils.decodeBase32("JBSWY3DPEHPK3PXP");
        String code = OtpUtils.generateTOTP(key, 999L, 8, "HmacSHA256");
        assertEquals(8, code.length());
    }

    @Test
    void parseOtpAuthUrlExtractsConfig() throws Exception {
        String url = "otpauth://totp/Acme:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Acme&algorithm=SHA256&digits=8&period=60";
        OtpUtils.OtpConfig cfg = OtpUtils.parseOtpAuthUrl(url);
        assertEquals("JBSWY3DPEHPK3PXP", cfg.secret);
        assertEquals("Acme", cfg.issuer);
        assertEquals("Acme:alice@example.com", cfg.label);
        assertEquals("SHA256", cfg.algorithm);
        assertEquals(8, cfg.digits);
        assertEquals(60, cfg.period);
    }

    @Test
    void parseOtpAuthUrlRejectsNonTotp() {
        assertThrows(IllegalArgumentException.class,
                () -> OtpUtils.parseOtpAuthUrl("otpauth://hotp/Label?secret=ABC"));
    }

    @Test
    void parseOtpAuthUrlRejectsMissingSecret() {
        assertThrows(IllegalArgumentException.class,
                () -> OtpUtils.parseOtpAuthUrl("otpauth://totp/Label?issuer=Acme"));
    }
}
