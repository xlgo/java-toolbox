package com.aqishi.toolbox.vault;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultDataTest {

    @Test
    void returnsScopedDefensiveCopies() {
        PasswordAccount password = new PasswordAccount(
                "GitHub", "dev", "secret", "https://github.com");
        TotpAccount totp = new TotpAccount(
                "totp-1", "Mail", "JBSWY3DPEHPK3PXP",
                "Example", "SHA1", 6, 30, true);
        VaultData data = new VaultData();
        List<PasswordAccount> passwordInput = new ArrayList<>(
                Collections.singletonList(password));
        List<TotpAccount> totpInput = new ArrayList<>(Collections.singletonList(totp));

        data.setPasswordAccounts(passwordInput);
        data.setTotpAccounts(totpInput);
        password.setPassword("changed-before-read");
        totp.setSecret("changed-before-read");
        passwordInput.clear();
        totpInput.clear();

        List<PasswordAccount> passwordCopy = data.copyPasswordAccounts();
        List<TotpAccount> totpCopy = data.copyTotpAccounts();
        passwordCopy.get(0).setPassword("changed-after-read");
        totpCopy.get(0).setSecret("changed-after-read");
        passwordCopy.clear();
        totpCopy.clear();

        assertEquals("secret", data.copyPasswordAccounts().get(0).getPassword());
        assertEquals("JBSWY3DPEHPK3PXP", data.copyTotpAccounts().get(0).getSecret());
        assertEquals(1, data.getPasswordAccounts().size());
        assertEquals(1, data.getTotpAccounts().size());
        assertNotSame(data.getPasswordAccounts(), data.getPasswordAccounts());
    }

    @Test
    void copyIsDeepAndNullCollectionsBecomeEmpty() {
        VaultData data = new VaultData();
        data.setPasswordAccounts(Collections.singletonList(
                new PasswordAccount("GitHub", "dev", "secret", "url")));
        data.setTotpAccounts(null);

        VaultData copy = data.copy();
        List<PasswordAccount> changed = copy.copyPasswordAccounts();
        changed.get(0).setUsername("other");
        copy.setPasswordAccounts(changed);

        assertEquals("dev", data.copyPasswordAccounts().get(0).getUsername());
        assertTrue(data.copyTotpAccounts().isEmpty());

        data.setPasswordAccounts(null);
        assertTrue(data.copyPasswordAccounts().isEmpty());
    }

    @Test
    void beansCopyEveryFieldAndNormalizeLegacyOptionalNulls() {
        PasswordAccount password = new PasswordAccount("name", null, null, null);
        PasswordAccount passwordCopy = password.copy();
        TotpAccount totp = new TotpAccount(
                "id", "label", "secret", null, "SHA256", 8, 45, false);
        TotpAccount totpCopy = totp.copy();

        assertEquals("", passwordCopy.getUsername());
        assertEquals("", passwordCopy.getPassword());
        assertEquals("", passwordCopy.getUrl());
        assertEquals("id", totpCopy.getId());
        assertEquals("label", totpCopy.getLabel());
        assertEquals("secret", totpCopy.getSecret());
        assertEquals("", totpCopy.getIssuer());
        assertEquals("SHA256", totpCopy.getAlgorithm());
        assertEquals(8, totpCopy.getDigits());
        assertEquals(45, totpCopy.getPeriod());
        assertEquals(false, totpCopy.isShowDirectly());
    }

    @Test
    void validatesSchemaVersionAndRecordLimits() {
        VaultData future = new VaultData();
        future.setSchemaVersion(VaultData.SCHEMA_VERSION + 1);
        assertCode(VaultErrorCode.UNSUPPORTED_FORMAT, future);

        VaultData malformed = new VaultData();
        malformed.setSchemaVersion(0);
        assertCode(VaultErrorCode.INVALID_ENVELOPE, malformed);

        VaultData tooManyPasswords = new VaultData();
        tooManyPasswords.setPasswordAccounts(Collections.nCopies(
                VaultData.MAX_RECORDS + 1, new PasswordAccount("n", "u", "p", "url")));
        assertCode(VaultErrorCode.INVALID_ENVELOPE, tooManyPasswords);

        VaultData tooManyTotps = new VaultData();
        tooManyTotps.setTotpAccounts(Collections.nCopies(
                VaultData.MAX_RECORDS + 1,
                new TotpAccount("id", "label", "secret", "issuer", "SHA1", 6, 30, true)));
        assertCode(VaultErrorCode.INVALID_ENVELOPE, tooManyTotps);
    }

    @Test
    void rejectsOverlongMetadataAndSecrets() {
        String tooLong = repeat('x', VaultData.MAX_TEXT_LENGTH + 1);
        List<VaultData> malformed = Arrays.asList(
                withPassword(new PasswordAccount(tooLong, "u", "p", "url")),
                withPassword(new PasswordAccount("n", tooLong, "p", "url")),
                withPassword(new PasswordAccount("n", "u", "p", tooLong)),
                withTotp(new TotpAccount(tooLong, "l", "s", "i", "SHA1", 6, 30, true)),
                withTotp(new TotpAccount("id", tooLong, "s", "i", "SHA1", 6, 30, true)),
                withTotp(new TotpAccount("id", "l", tooLong, "i", "SHA1", 6, 30, true)),
                withTotp(new TotpAccount("id", "l", "s", tooLong, "SHA1", 6, 30, true)));

        for (VaultData data : malformed) {
            assertCode(VaultErrorCode.INVALID_ENVELOPE, data);
        }
    }

    @Test
    void rejectsStoredPasswordsOverOneMebibyte() {
        VaultData data = withPassword(new PasswordAccount(
                "name", "user", repeat('p', VaultData.MAX_PASSWORD_LENGTH + 1), "url"));

        assertCode(VaultErrorCode.INVALID_ENVELOPE, data);
    }

    @Test
    void measuresStoredPasswordLimitInUtf8Bytes() {
        VaultData data = withPassword(new PasswordAccount(
                "name", "user", repeat('\u4e2d', 400_000), "url"));

        assertCode(VaultErrorCode.INVALID_ENVELOPE, data);
    }

    @Test
    void rejectsNullRecordsAndRequiredFields() {
        VaultData nullRecord = new VaultData();
        nullRecord.setPasswordAccounts(Collections.singletonList(null));
        assertCode(VaultErrorCode.INVALID_ENVELOPE, nullRecord);

        assertCode(VaultErrorCode.INVALID_ENVELOPE,
                withPassword(new PasswordAccount(null, "", "", "")));
        assertCode(VaultErrorCode.INVALID_ENVELOPE,
                withTotp(new TotpAccount(null, "label", "secret", "", "SHA1", 6, 30, true)));
        assertCode(VaultErrorCode.INVALID_ENVELOPE,
                withTotp(new TotpAccount("id", null, "secret", "", "SHA1", 6, 30, true)));
        assertCode(VaultErrorCode.INVALID_ENVELOPE,
                withTotp(new TotpAccount("id", "label", null, "", "SHA1", 6, 30, true)));
    }

    @Test
    void vaultExceptionExposesStableCodeAndRetryability() {
        VaultException error = new VaultException(
                VaultErrorCode.READ_FAILED, "read failed", true,
                new IllegalStateException("cause"));

        assertEquals(VaultErrorCode.READ_FAILED, error.getCode());
        assertTrue(error.isRetryable());
        assertEquals("cause", error.getCause().getMessage());
    }

    private static VaultData withPassword(PasswordAccount account) {
        VaultData data = new VaultData();
        data.setPasswordAccounts(Collections.singletonList(account));
        return data;
    }

    private static VaultData withTotp(TotpAccount account) {
        VaultData data = new VaultData();
        data.setTotpAccounts(Collections.singletonList(account));
        return data;
    }

    private static void assertCode(VaultErrorCode expected, VaultData data) {
        VaultException error = assertThrows(VaultException.class, data::validate);
        assertEquals(expected, error.getCode());
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
