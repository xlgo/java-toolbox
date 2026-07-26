package com.aqishi.toolbox.vault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Read-only decoder for the password-manager format used before the unified vault. */
public final class LegacyPasswordReader {
    private static final String MAGIC = "TOOLBOX_PWD_MGR";
    private final ObjectMapper mapper = new ObjectMapper();

    public List<PasswordAccount> read(Path path, char[] password) throws VaultException {
        byte[] passwordBytes = null;
        byte[] hash = null;
        byte[] key = null;
        byte[] encoded = null;
        byte[] plaintext = null;
        try {
            ByteBuffer utf8 = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
            passwordBytes = new byte[utf8.remaining()];
            utf8.get(passwordBytes);
            hash = MessageDigest.getInstance("SHA-256").digest(passwordBytes);
            key = Arrays.copyOf(hash, 16);
            encoded = Files.readAllBytes(path);
            if (encoded.length <= 16) {
                throw invalidLegacy("Legacy password file is truncated", null);
            }
            byte[] iv = Arrays.copyOfRange(encoded, 0, 16);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(iv));
            plaintext = cipher.doFinal(Arrays.copyOfRange(encoded, 16, encoded.length));

            JsonNode root = mapper.readTree(plaintext);
            if (root == null || !MAGIC.equals(root.path("magic").asText())
                    || !root.path("accounts").isArray()) {
                throw invalidLegacy("Legacy password data is invalid", null);
            }
            List<PasswordAccount> accounts = new ArrayList<>();
            for (JsonNode node : root.path("accounts")) {
                if (!node.hasNonNull("name")) {
                    throw invalidLegacy("Legacy password account is invalid", null);
                }
                accounts.add(new PasswordAccount(
                        node.path("name").asText(),
                        node.path("username").asText(""),
                        node.path("password").asText(""),
                        node.path("url").asText("")));
            }
            return accounts;
        } catch (VaultException error) {
            throw error;
        } catch (Exception error) {
            throw invalidLegacy("Unable to authenticate legacy password data", error);
        } finally {
            VaultCrypto.wipe(password);
            VaultCrypto.wipe(passwordBytes);
            VaultCrypto.wipe(hash);
            VaultCrypto.wipe(key);
            VaultCrypto.wipe(encoded);
            VaultCrypto.wipe(plaintext);
        }
    }

    private static VaultException invalidLegacy(String message, Throwable cause) {
        return cause == null
                ? new VaultException(VaultErrorCode.AUTHENTICATION_FAILED, message, true)
                : new VaultException(VaultErrorCode.AUTHENTICATION_FAILED,
                message, true, cause);
    }
}
