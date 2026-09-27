package com.aqishi.toolbox.feature.monitor.domain;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * AES-256-GCM record protection for an established secure channel.
 *
 * <p>Record = payload of a {@link DesktopMessage#TYPE_SECURE_RECORD} frame:</p>
 * <pre>
 *  seq(8, big-endian) | AES-256-GCM(key_dir, nonce_dir(seq), aad, appType(1) || appPayload) | tag(16)
 *  nonce_dir(seq) = IV_dir XOR (0^4 || seq)          -- TLS 1.3 style, unique per record
 *  aad            = "JTRS" | version(1) | direction(1) | seq(8)
 * </pre>
 * <p>Each direction has its own key and IV and its own counter starting at 0,
 * so a nonce never repeats under a key: the sender refuses to go past
 * {@link #MAX_SEQUENCE}. With {@link Ordering#STRICT} (TCP) the receiver only
 * accepts exactly the next sequence number, rejecting replays, drops and
 * reordering. With {@link Ordering#WINDOWED} (UDP) a {@link ReplayWindow}
 * accepts loss and reordering but never the same record twice.</p>
 */
public final class RecordCipher {

    public enum Ordering { STRICT, WINDOWED }

    public static final int SEQ_LEN = 8;
    public static final int TAG_LEN = 16;
    /** Bytes a record adds on top of the application payload. */
    public static final int OVERHEAD = SEQ_LEN + 1 + TAG_LEN;
    public static final long MAX_SEQUENCE = 1L << 48;

    private static final byte DIR_CONTROLLER_TO_HOST = 1;
    private static final byte DIR_HOST_TO_CONTROLLER = 2;

    /** A record that failed the sequence check (replay, reorder or too old). */
    public static final class RecordRejectedException extends GeneralSecurityException {
        private static final long serialVersionUID = 1L;

        public RecordRejectedException(String message) {
            super(message);
        }
    }

    private final Ordering ordering;
    private final Object sendLock = new Object();
    private final Object receiveLock = new Object();
    private final SecretKeySpec sendKey;
    private final SecretKeySpec receiveKey;
    private final byte[] sendIv;
    private final byte[] receiveIv;
    private final byte sendDirection;
    private final byte receiveDirection;
    private final Cipher sendCipher;
    private final Cipher receiveCipher;
    private final ReplayWindow window = new ReplayWindow();
    private long nextSendSequence;
    private long nextReceiveSequence;

    private RecordCipher(Ordering ordering, byte[] sendKey, byte[] sendIv, byte sendDirection,
                         byte[] receiveKey, byte[] receiveIv, byte receiveDirection)
            throws GeneralSecurityException {
        this.ordering = ordering;
        this.sendKey = new SecretKeySpec(sendKey, "AES");
        this.receiveKey = new SecretKeySpec(receiveKey, "AES");
        this.sendIv = sendIv;
        this.receiveIv = receiveIv;
        this.sendDirection = sendDirection;
        this.receiveDirection = receiveDirection;
        this.sendCipher = Cipher.getInstance("AES/GCM/NoPadding");
        this.receiveCipher = Cipher.getInstance("AES/GCM/NoPadding");
        Arrays.fill(sendKey, (byte) 0);
        Arrays.fill(receiveKey, (byte) 0);
    }

    /** Builds the cipher for one end of the channel. {@code keys} may be destroyed afterwards. */
    public static RecordCipher create(SessionKeys keys, SecureChannelHandshake.Role role, Ordering ordering)
            throws GeneralSecurityException {
        if (role == SecureChannelHandshake.Role.CONTROLLER) {
            return new RecordCipher(ordering,
                    keys.controllerToHostKey(), keys.controllerToHostIv(), DIR_CONTROLLER_TO_HOST,
                    keys.hostToControllerKey(), keys.hostToControllerIv(), DIR_HOST_TO_CONTROLLER);
        }
        return new RecordCipher(ordering,
                keys.hostToControllerKey(), keys.hostToControllerIv(), DIR_HOST_TO_CONTROLLER,
                keys.controllerToHostKey(), keys.controllerToHostIv(), DIR_CONTROLLER_TO_HOST);
    }

    /** Encrypts one application message into a record. */
    public byte[] seal(byte type, byte[] payload) throws GeneralSecurityException {
        byte[] body = payload == null ? new byte[0] : payload;
        synchronized (sendLock) {
            long seq = nextSendSequence;
            if (seq >= MAX_SEQUENCE) {
                throw new IllegalStateException("record sequence space exhausted; reconnect");
            }
            nextSendSequence = seq + 1;
            byte[] record = new byte[SEQ_LEN + 1 + body.length + TAG_LEN];
            ByteBuffer.wrap(record).putLong(seq);
            sendCipher.init(Cipher.ENCRYPT_MODE, sendKey, new GCMParameterSpec(TAG_LEN * 8, nonce(sendIv, seq)));
            sendCipher.updateAAD(aad(sendDirection, seq));
            int written = sendCipher.update(new byte[]{type}, 0, 1, record, SEQ_LEN);
            written += sendCipher.doFinal(body, 0, body.length, record, SEQ_LEN + written);
            if (written != 1 + body.length + TAG_LEN) {
                throw new GeneralSecurityException("unexpected GCM output length");
            }
            return record;
        }
    }

    /** Authenticates and decrypts one record; throws on tampering, replay or reordering. */
    public DesktopMessage open(byte[] record) throws GeneralSecurityException {
        if (record == null || record.length < OVERHEAD) {
            throw new RecordRejectedException("record too short");
        }
        long seq = ByteBuffer.wrap(record).getLong();
        synchronized (receiveLock) {
            if (ordering == Ordering.STRICT ? seq != nextReceiveSequence : !window.check(seq)) {
                throw new RecordRejectedException("unexpected record sequence " + seq);
            }
            receiveCipher.init(Cipher.DECRYPT_MODE, receiveKey,
                    new GCMParameterSpec(TAG_LEN * 8, nonce(receiveIv, seq)));
            receiveCipher.updateAAD(aad(receiveDirection, seq));
            byte[] plain = receiveCipher.doFinal(record, SEQ_LEN, record.length - SEQ_LEN);
            if (ordering == Ordering.STRICT) nextReceiveSequence = seq + 1;
            else window.mark(seq);
            return new DesktopMessage(plain[0], Arrays.copyOfRange(plain, 1, plain.length));
        }
    }

    /** Next sequence number the sender will use (for tests and diagnostics). */
    public long nextSendSequence() {
        synchronized (sendLock) {
            return nextSendSequence;
        }
    }

    /** Wipes the nonce bases; the cipher must not be used afterwards. */
    public void destroy() {
        synchronized (sendLock) {
            Arrays.fill(sendIv, (byte) 0);
            nextSendSequence = MAX_SEQUENCE;
        }
        synchronized (receiveLock) {
            Arrays.fill(receiveIv, (byte) 0);
        }
    }

    private static byte[] nonce(byte[] iv, long seq) {
        byte[] nonce = iv.clone();
        for (int i = 0; i < 8; i++) {
            nonce[nonce.length - 1 - i] ^= (byte) (seq >>> (8 * i));
        }
        return nonce;
    }

    private static byte[] aad(byte direction, long seq) {
        return ByteBuffer.allocate(14).putInt(HandshakeWire.MAGIC).put(HandshakeWire.VERSION)
                .put(direction).putLong(seq).array();
    }
}
