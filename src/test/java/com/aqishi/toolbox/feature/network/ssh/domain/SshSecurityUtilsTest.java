package com.aqishi.toolbox.feature.network.ssh.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SSH 凭据加密核心 round-trip 测试（AES-GCM v2 格式）。
 */
class SshSecurityUtilsTest {

    @Test
    void encryptDecryptRoundTrip() {
        String plain = "root@192.168.11.51:p@ssw0rd!具身智能";
        String cipher = SshSecurityUtils.encrypt(plain);
        assertTrue(SshSecurityUtils.isEncrypted(cipher));
        assertNotEquals(plain, cipher);
        assertEquals(plain, SshSecurityUtils.decrypt(cipher));
    }

    @Test
    void eachEncryptionIsNonDeterministicCiphertext() {
        String plain = "same-secret";
        String a = SshSecurityUtils.encrypt(plain);
        String b = SshSecurityUtils.encrypt(plain);
        assertNotEquals(a, b);
        assertEquals(plain, SshSecurityUtils.decrypt(a));
        assertEquals(plain, SshSecurityUtils.decrypt(b));
    }

    @Test
    void emptyInputRoundTripsToEmpty() {
        assertEquals("", SshSecurityUtils.encrypt(""));
        assertEquals("", SshSecurityUtils.decrypt(""));
        assertFalse(SshSecurityUtils.isEncrypted(""));
    }

    @Test
    void tamperedCiphertextRejected() {
        String cipher = SshSecurityUtils.encrypt("top-secret");
        // 翻转最后一段的一个 base64 字符以破坏 GCM 标签
        int lastDot = cipher.lastIndexOf('.');
        StringBuilder sb = new StringBuilder(cipher);
        char c = sb.charAt(lastDot + 1);
        sb.setCharAt(lastDot + 1, c == 'A' ? 'B' : 'A');
        String tampered = sb.toString();
        assertThrows(IllegalArgumentException.class, () -> SshSecurityUtils.decrypt(tampered));
    }

    @Test
    void malformedCiphertextRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SshSecurityUtils.decrypt("v2.only.two.parts.here"));
    }

    @Test
    void migratePlainToV2() {
        String plain = "legacy-plain-secret";
        String migrated = SshSecurityUtils.migrate(plain);
        assertTrue(SshSecurityUtils.isEncrypted(migrated));
        assertEquals(plain, SshSecurityUtils.decrypt(migrated));
    }

    @Test
    void migrateLeavesV2Unchanged() {
        String v2 = SshSecurityUtils.encrypt("already-v2");
        assertEquals(v2, SshSecurityUtils.migrate(v2));
    }

    @Test
    void migrateLeavesNullEmptyUnchanged() {
        assertEquals("", SshSecurityUtils.migrate(null));
        assertEquals("", SshSecurityUtils.migrate(""));
    }
}
