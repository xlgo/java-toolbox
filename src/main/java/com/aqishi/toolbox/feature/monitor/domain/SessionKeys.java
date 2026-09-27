package com.aqishi.toolbox.feature.monitor.domain;

import java.util.Arrays;

/**
 * Key material produced by {@link SecureChannelHandshake}.
 *
 * <p>Holds one AES-256 key and one 96-bit nonce base per direction
 * (controller-to-host and host-to-controller), the transcript hash and the
 * SAS derived from it. Call {@link #destroy()} once a {@link RecordCipher} has
 * been built from it.</p>
 */
public final class SessionKeys {

    private final byte[] controllerToHostKey;
    private final byte[] controllerToHostIv;
    private final byte[] hostToControllerKey;
    private final byte[] hostToControllerIv;
    private final byte[] transcriptHash;
    private final String sas;
    private final boolean passwordUsed;

    SessionKeys(byte[] controllerToHostKey, byte[] controllerToHostIv,
                byte[] hostToControllerKey, byte[] hostToControllerIv,
                byte[] transcriptHash, boolean passwordUsed) {
        this.controllerToHostKey = controllerToHostKey;
        this.controllerToHostIv = controllerToHostIv;
        this.hostToControllerKey = hostToControllerKey;
        this.hostToControllerIv = hostToControllerIv;
        this.transcriptHash = transcriptHash;
        this.sas = Sas.fromTranscript(transcriptHash);
        this.passwordUsed = passwordUsed;
    }

    public byte[] controllerToHostKey() {
        return controllerToHostKey.clone();
    }

    public byte[] controllerToHostIv() {
        return controllerToHostIv.clone();
    }

    public byte[] hostToControllerKey() {
        return hostToControllerKey.clone();
    }

    public byte[] hostToControllerIv() {
        return hostToControllerIv.clone();
    }

    public byte[] transcriptHash() {
        return transcriptHash.clone();
    }

    /** Short authentication string, e.g. {@code "123 456"}. */
    public String sas() {
        return sas;
    }

    /** Whether an access password was mixed into the key derivation. */
    public boolean isPasswordUsed() {
        return passwordUsed;
    }

    /** Overwrites all secret key material. The SAS stays readable. */
    public void destroy() {
        Arrays.fill(controllerToHostKey, (byte) 0);
        Arrays.fill(controllerToHostIv, (byte) 0);
        Arrays.fill(hostToControllerKey, (byte) 0);
        Arrays.fill(hostToControllerIv, (byte) 0);
    }
}
