package com.aqishi.toolbox.feature.monitor.domain;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Authenticated key exchange for the remote-desktop data channel
 * (protocol "JTRS" version 1). Pure state machine: no I/O, no threads.
 *
 * <h2>Messages</h2>
 * Each message is the payload of one transport frame of type
 * {@link DesktopMessage#TYPE_SECURE_HANDSHAKE}. All start with the header
 * {@code magic "JTRS"(4) | version(1) | kind(1)}:
 * <pre>
 *  C->H  COMMIT            header | SHA-256("JTRS commit v1" || HELLO_C)            (38 bytes)
 *  H->C  HOST_HELLO        header | flags(1) | X25519 pubH(32) | nonceH(32)          (71 bytes)
 *  C->H  CONTROLLER_HELLO  HELLO_C = header | flags(1) | pubC(32) | nonceC(32),
 *                          followed by FIN_C(32)                                     (103 bytes)
 *  H->C  HOST_FINISHED     header | FIN_H(32)                                        (38 bytes)
 *  any   ALERT             header | code(1): 1 version, 2 auth, 3 password required, 4 protocol
 * </pre>
 * flags bit 0: host = "access password required", controller = "password supplied".
 * Roles are fixed by the application (the controlled machine is always HOST),
 * not by who opened the transport, and each kind is only valid from one role,
 * so reflected messages are rejected.
 *
 * <h2>Key schedule</h2>
 * <pre>
 *  lp(x)  = uint32_be(len(x)) || x
 *  TH     = SHA-256("java-toolbox remote-desktop secure channel v1"
 *                   || lp(controllerId) || lp(hostId) || lp(COMMIT) || lp(HOST_HELLO) || lp(HELLO_C))
 *  DH     = X25519(own ephemeral, peer ephemeral)       (all-zero result rejected)
 *  PSK    = host requires password ? PBKDF2-HMAC-SHA256(password, salt = TH, 100000, 32) : 0^32
 *  PRK    = HKDF-Extract(salt = TH, IKM = DH || PSK)
 *  K_c2h, K_h2c = HKDF-Expand(PRK, "c2h key" / "h2c key", 32)      AES-256-GCM keys
 *  IV_c2h, IV_h2c = HKDF-Expand(PRK, "c2h iv" / "h2c iv", 12)       nonce bases
 *  FK_c, FK_h = HKDF-Expand(PRK, "c finished" / "h finished", 32)
 *  FIN_C  = HMAC-SHA256(FK_c, TH);   FIN_H = HMAC-SHA256(FK_h, TH || FIN_C)
 *  SAS    = uint32(SHA-256("java-toolbox remote-desktop sas v1" || TH)[0..4]) mod 10^6
 * </pre>
 * The controller and host session IDs (from signaling) are bound into TH, so a
 * peer that does not know both IDs, the password (if set) and cannot complete
 * the X25519 exchange fails key confirmation. FIN values are compared with
 * {@link MessageDigest#isEqual}.
 *
 * <h2>Security notes</h2>
 * <ul>
 *   <li>The exchange is unauthenticated Diffie-Hellman; MITM resistance comes
 *   from users comparing the SAS (and from the host's consent dialog). The
 *   COMMIT message forces the controller to fix its key before it sees the
 *   host's, so an attacker cannot grind keys until two SAS values collide.</li>
 *   <li>The access password is a PSK. It never crosses the wire, but an
 *   active MITM who completed DH with the controller can test password guesses
 *   offline against FIN_C. PBKDF2 slows that down; a low-entropy password is
 *   still brute-forceable, so the SAS check stays the primary MITM defence.
 *   (J-PAKE would avoid the offline attack, but BouncyCastle's implementation
 *   needs two extra round trips over large prime-order groups and a
 *   hand-rolled key confirmation, and the SAS already covers MITM.)</li>
 *   <li>Every failure is fatal; the transport must be closed.</li>
 * </ul>
 */
public final class SecureChannelHandshake {

    public enum Role { CONTROLLER, HOST }

    private enum State { INIT, AWAIT_COMMIT, AWAIT_HOST_HELLO, AWAIT_CONTROLLER_HELLO, AWAIT_HOST_FINISHED, COMPLETE, FAILED }

    static final int PASSWORD_ITERATIONS = 100_000;
    private static final byte[] TRANSCRIPT_LABEL =
            "java-toolbox remote-desktop secure channel v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COMMIT_LABEL = "JTRS commit v1".getBytes(StandardCharsets.US_ASCII);

    private final Role role;
    private final byte[] controllerId;
    private final byte[] hostId;
    private final char[] password;
    private final SecureRandom random;

    private State state = State.INIT;
    private KeyPair ephemeral;
    private byte[] ownHello;
    private byte[] commit;
    private byte[] hostHello;
    private byte[] controllerFinished;
    private byte[] hostFinishedKey;
    private byte[] transcriptHash;
    private SessionKeys keys;

    /**
     * @param password access password, or null/empty for none. Copied; the
     *                 caller may wipe its own array afterwards.
     */
    public SecureChannelHandshake(Role role, String controllerId, String hostId,
                                  char[] password, SecureRandom random) {
        if (role == null || controllerId == null || hostId == null || random == null) {
            throw new IllegalArgumentException("role, ids and random are required");
        }
        this.role = role;
        this.controllerId = controllerId.getBytes(StandardCharsets.UTF_8);
        this.hostId = hostId.getBytes(StandardCharsets.UTF_8);
        this.password = password == null || password.length == 0 ? null : password.clone();
        this.random = random;
    }

    public Role role() {
        return role;
    }

    /** Returns the first flight (COMMIT) for the controller, or null for the host. */
    public synchronized byte[] start() throws HandshakeException {
        if (state != State.INIT) throw fail(HandshakeException.Reason.PROTOCOL_ERROR, "already started");
        if (role == Role.HOST) {
            state = State.AWAIT_COMMIT;
            return null;
        }
        try {
            ownHello = newHello(HandshakeWire.KIND_CONTROLLER_HELLO, password != null);
            commit = HandshakeWire.withHash(HandshakeWire.KIND_COMMIT, commitmentOf(ownHello));
            state = State.AWAIT_HOST_HELLO;
            return commit.clone();
        } catch (GeneralSecurityException e) {
            throw fail(HandshakeException.Reason.PROTOCOL_ERROR, e.getMessage());
        }
    }

    /** Processes one peer message and returns the reply to send, or null. */
    public synchronized byte[] receive(byte[] message) throws HandshakeException {
        try {
            byte kind = HandshakeWire.parseKind(message);
            switch (state) {
                case AWAIT_COMMIT:
                    return onCommit(kind, message);
                case AWAIT_HOST_HELLO:
                    return onHostHello(kind, message);
                case AWAIT_CONTROLLER_HELLO:
                    return onControllerHello(kind, message);
                case AWAIT_HOST_FINISHED:
                    onHostFinished(kind, message);
                    return null;
                default:
                    throw new HandshakeException(HandshakeException.Reason.PROTOCOL_ERROR,
                            "unexpected handshake message in state " + state);
            }
        } catch (HandshakeException e) {
            state = State.FAILED;
            wipe();
            throw e;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw fail(HandshakeException.Reason.PROTOCOL_ERROR, e.getClass().getSimpleName());
        }
    }

    public synchronized boolean isComplete() {
        return state == State.COMPLETE;
    }

    /** Derived keys; only available once {@link #isComplete()}. */
    public synchronized SessionKeys keys() {
        if (state != State.COMPLETE) throw new IllegalStateException("handshake not complete");
        return keys;
    }

    /** Encodes an ALERT carrying {@code reason} (for reasons that have a wire code). */
    public static byte[] alert(HandshakeException.Reason reason) {
        return reason.alertCode() == 0 ? null : HandshakeWire.alert(reason);
    }

    private byte[] onCommit(byte kind, byte[] message) throws HandshakeException, GeneralSecurityException {
        expect(kind, HandshakeWire.KIND_COMMIT, message, HandshakeWire.COMMIT_LEN);
        commit = message.clone();
        ownHello = newHello(HandshakeWire.KIND_HOST_HELLO, password != null);
        hostHello = ownHello;
        state = State.AWAIT_CONTROLLER_HELLO;
        return hostHello.clone();
    }

    private byte[] onHostHello(byte kind, byte[] message) throws HandshakeException, GeneralSecurityException {
        expect(kind, HandshakeWire.KIND_HOST_HELLO, message, HandshakeWire.HELLO_LEN);
        hostHello = message.clone();
        boolean hostRequiresPassword = (message[HandshakeWire.FLAGS_OFFSET] & HandshakeWire.FLAG_PASSWORD) != 0;
        if (hostRequiresPassword && password == null) {
            throw new HandshakeException(HandshakeException.Reason.PASSWORD_REQUIRED, "host requires a password");
        }
        deriveKeys(peerPublicKey(message), hostRequiresPassword, ownHello);
        ByteBuffer reply = ByteBuffer.allocate(HandshakeWire.CONTROLLER_HELLO_LEN);
        reply.put(ownHello).put(controllerFinished);
        state = State.AWAIT_HOST_FINISHED;
        return reply.array();
    }

    private byte[] onControllerHello(byte kind, byte[] message) throws HandshakeException, GeneralSecurityException {
        expect(kind, HandshakeWire.KIND_CONTROLLER_HELLO, message, HandshakeWire.CONTROLLER_HELLO_LEN);
        byte[] controllerHello = HandshakeWire.slice(message, 0, HandshakeWire.HELLO_LEN);
        byte[] committed = HandshakeWire.slice(commit, HandshakeWire.HEADER_LEN, HandshakeWire.HASH_LEN);
        if (!MessageDigest.isEqual(committed, commitmentOf(controllerHello))) {
            throw new HandshakeException(HandshakeException.Reason.AUTH_FAILED, "commitment mismatch");
        }
        boolean supplied = (controllerHello[HandshakeWire.FLAGS_OFFSET] & HandshakeWire.FLAG_PASSWORD) != 0;
        if (password != null && !supplied) {
            throw new HandshakeException(HandshakeException.Reason.PASSWORD_REQUIRED, "controller sent no password");
        }
        deriveKeys(peerPublicKey(controllerHello), password != null, controllerHello);
        byte[] received = HandshakeWire.slice(message, HandshakeWire.HELLO_LEN, HandshakeWire.HASH_LEN);
        if (!MessageDigest.isEqual(received, controllerFinished)) {
            throw new HandshakeException(HandshakeException.Reason.AUTH_FAILED, "controller key confirmation failed");
        }
        byte[] hostFinished = Hkdf.hmac(hostFinishedKey, concat(transcriptHash, controllerFinished));
        state = State.COMPLETE;
        wipeSecrets();
        return HandshakeWire.withHash(HandshakeWire.KIND_HOST_FINISHED, hostFinished);
    }

    private void onHostFinished(byte kind, byte[] message) throws HandshakeException, GeneralSecurityException {
        expect(kind, HandshakeWire.KIND_HOST_FINISHED, message, HandshakeWire.HOST_FINISHED_LEN);
        byte[] expected = Hkdf.hmac(hostFinishedKey, concat(transcriptHash, controllerFinished));
        byte[] received = HandshakeWire.slice(message, HandshakeWire.HEADER_LEN, HandshakeWire.HASH_LEN);
        if (!MessageDigest.isEqual(expected, received)) {
            throw new HandshakeException(HandshakeException.Reason.AUTH_FAILED, "host key confirmation failed");
        }
        state = State.COMPLETE;
        wipeSecrets();
    }

    private void deriveKeys(byte[] peerPublicKey, boolean usePassword, byte[] controllerHello)
            throws GeneralSecurityException {
        transcriptHash = transcript(controllerHello);
        byte[] dh = X25519Keys.agree(ephemeral.getPrivate(), peerPublicKey);
        ephemeral = null;
        byte[] psk = usePassword ? stretchPassword(transcriptHash) : new byte[32];
        byte[] ikm = concat(dh, psk);
        byte[] prk = Hkdf.extract(transcriptHash, ikm);
        try {
            keys = new SessionKeys(
                    Hkdf.expand(prk, "c2h key", 32), Hkdf.expand(prk, "c2h iv", 12),
                    Hkdf.expand(prk, "h2c key", 32), Hkdf.expand(prk, "h2c iv", 12),
                    transcriptHash.clone(), usePassword);
            byte[] controllerFinishedKey = Hkdf.expand(prk, "c finished", 32);
            hostFinishedKey = Hkdf.expand(prk, "h finished", 32);
            controllerFinished = Hkdf.hmac(controllerFinishedKey, transcriptHash);
            Arrays.fill(controllerFinishedKey, (byte) 0);
        } finally {
            Arrays.fill(dh, (byte) 0);
            Arrays.fill(psk, (byte) 0);
            Arrays.fill(ikm, (byte) 0);
            Arrays.fill(prk, (byte) 0);
        }
    }

    private byte[] stretchPassword(byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PASSWORD_ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private byte[] transcript(byte[] controllerHello) throws GeneralSecurityException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        out.writeBytes(TRANSCRIPT_LABEL);
        for (byte[] part : new byte[][]{controllerId, hostId, commit, hostHello, controllerHello}) {
            out.writeBytes(ByteBuffer.allocate(4).putInt(part.length).array());
            out.writeBytes(part);
        }
        return MessageDigest.getInstance("SHA-256").digest(out.toByteArray());
    }

    private byte[] newHello(byte kind, boolean passwordFlag) throws GeneralSecurityException {
        ephemeral = X25519Keys.generate(random);
        byte[] nonce = new byte[HandshakeWire.NONCE_LEN];
        random.nextBytes(nonce);
        byte flags = passwordFlag ? HandshakeWire.FLAG_PASSWORD : 0;
        return HandshakeWire.hello(kind, flags, X25519Keys.rawPublicKey(ephemeral.getPublic()), nonce);
    }

    private static byte[] commitmentOf(byte[] controllerHello) throws GeneralSecurityException {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(COMMIT_LABEL);
        return sha.digest(controllerHello);
    }

    private static byte[] peerPublicKey(byte[] hello) {
        return HandshakeWire.slice(hello, HandshakeWire.PUBLIC_KEY_OFFSET, X25519Keys.KEY_LEN);
    }

    private static void expect(byte kind, byte expectedKind, byte[] message, int length) throws HandshakeException {
        if (kind != expectedKind) {
            throw new HandshakeException(HandshakeException.Reason.PROTOCOL_ERROR,
                    "expected handshake kind " + expectedKind + " but got " + kind);
        }
        HandshakeWire.requireLength(message, length);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private HandshakeException fail(HandshakeException.Reason reason, String detail) {
        state = State.FAILED;
        wipe();
        return new HandshakeException(reason, detail);
    }

    private void wipeSecrets() {
        if (hostFinishedKey != null) Arrays.fill(hostFinishedKey, (byte) 0);
        if (password != null) Arrays.fill(password, '\0');
        ephemeral = null;
    }

    private void wipe() {
        wipeSecrets();
        if (keys != null) keys.destroy();
    }
}
