package com.aqishi.toolbox.vault;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Copies sensitive text and clears it after 30 seconds only when unchanged. */
public final class SecureClipboard {
    private final ClipboardGateway gateway;
    private final VaultScheduler scheduler;

    public SecureClipboard(VaultScheduler scheduler) {
        this(new AwtClipboardGateway(), scheduler);
    }

    SecureClipboard(ClipboardGateway gateway, VaultScheduler scheduler) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public void copySensitive(String value) throws VaultException {
        Objects.requireNonNull(value, "value");
        final byte[] expected = digest(value);
        try {
            gateway.writeText(value);
            scheduler.schedule(() -> clearIfUnchanged(expected), 30, TimeUnit.SECONDS);
        } catch (Exception error) {
            VaultCrypto.wipe(expected);
            throw clipboardFailure(error);
        }
    }

    private void clearIfUnchanged(byte[] expected) {
        byte[] currentDigest = null;
        try {
            String current = gateway.readText();
            currentDigest = digest(current == null ? "" : current);
            if (MessageDigest.isEqual(expected, currentDigest)) {
                gateway.clear();
            }
        } catch (Exception ignored) {
            // Clipboard ownership is best-effort; never disturb newer user content.
        } finally {
            VaultCrypto.wipe(expected);
            VaultCrypto.wipe(currentDigest);
        }
    }

    private static byte[] digest(String value) throws VaultException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        try {
            return MessageDigest.getInstance("SHA-256").digest(encoded);
        } catch (Exception error) {
            throw new VaultException(VaultErrorCode.UNSUPPORTED_FORMAT,
                    "SHA-256 is unavailable", false, error);
        } finally {
            VaultCrypto.wipe(encoded);
        }
    }

    private static VaultException clipboardFailure(Throwable cause) {
        return new VaultException(VaultErrorCode.WRITE_FAILED,
                "Clipboard is temporarily unavailable", true, cause);
    }

    interface ClipboardGateway {
        String readText() throws Exception;

        void writeText(String value) throws Exception;

        void clear() throws Exception;
    }

    private static final class AwtClipboardGateway implements ClipboardGateway {
        private final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();

        @Override
        public String readText() throws Exception {
            if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                return "";
            }
            Object value = clipboard.getData(DataFlavor.stringFlavor);
            return value == null ? "" : value.toString();
        }

        @Override
        public void writeText(String value) {
            clipboard.setContents(new StringSelection(value), null);
        }

        @Override
        public void clear() {
            clipboard.setContents(new StringSelection(""), null);
        }
    }
}
