package com.aqishi.toolbox.feature.monitor.domain;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Byte layout of the handshake messages; see {@link SecureChannelHandshake}
 * for the protocol they implement.
 */
final class HandshakeWire {

    /** ASCII "JTRS" (java-toolbox remote secure). */
    static final int MAGIC = 0x4A545253;
    /** Secure channel protocol version. Bump on any incompatible change. */
    static final byte VERSION = 1;

    static final byte KIND_COMMIT = 1;
    static final byte KIND_HOST_HELLO = 2;
    static final byte KIND_CONTROLLER_HELLO = 3;
    static final byte KIND_HOST_FINISHED = 4;
    static final byte KIND_ALERT = 5;

    static final byte FLAG_PASSWORD = 0x01;

    static final int HEADER_LEN = 6;
    static final int HASH_LEN = 32;
    static final int NONCE_LEN = 32;
    static final int COMMIT_LEN = HEADER_LEN + HASH_LEN;
    static final int HELLO_LEN = HEADER_LEN + 1 + X25519Keys.KEY_LEN + NONCE_LEN;
    static final int CONTROLLER_HELLO_LEN = HELLO_LEN + HASH_LEN;
    static final int HOST_FINISHED_LEN = HEADER_LEN + HASH_LEN;
    static final int ALERT_LEN = HEADER_LEN + 1;
    /** Largest handshake message; anything bigger is rejected before parsing. */
    static final int MAX_MESSAGE_LEN = CONTROLLER_HELLO_LEN;

    static final int FLAGS_OFFSET = HEADER_LEN;
    static final int PUBLIC_KEY_OFFSET = HEADER_LEN + 1;

    private HandshakeWire() {
    }

    static ByteBuffer begin(byte kind, int totalLength) {
        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        buffer.putInt(MAGIC).put(VERSION).put(kind);
        return buffer;
    }

    static byte[] hello(byte kind, byte flags, byte[] publicKey, byte[] nonce) {
        return begin(kind, HELLO_LEN).put(flags).put(publicKey).put(nonce).array();
    }

    static byte[] withHash(byte kind, byte[] hash) {
        return begin(kind, HEADER_LEN + HASH_LEN).put(hash).array();
    }

    static byte[] alert(HandshakeException.Reason reason) {
        return begin(KIND_ALERT, ALERT_LEN).put((byte) reason.alertCode()).array();
    }

    /**
     * Validates magic and version and returns the message kind. An ALERT is
     * accepted at any version (so a peer on another version can still tell
     * us why it gave up) and is turned straight into the peer's failure.
     */
    static byte parseKind(byte[] message) throws HandshakeException {
        if (message == null || message.length < HEADER_LEN || message.length > MAX_MESSAGE_LEN) {
            throw new HandshakeException(HandshakeException.Reason.PROTOCOL_ERROR,
                    "bad handshake message length");
        }
        ByteBuffer buffer = ByteBuffer.wrap(message);
        if (buffer.getInt() != MAGIC) {
            throw new HandshakeException(HandshakeException.Reason.PROTOCOL_ERROR, "bad magic");
        }
        byte version = buffer.get();
        byte kind = buffer.get();
        if (kind == KIND_ALERT && message.length == ALERT_LEN) {
            HandshakeException.Reason reason =
                    HandshakeException.Reason.fromAlertCode(message[HEADER_LEN] & 0xFF);
            throw new HandshakeException(reason, "reported by peer (peer version " + version + ")", true);
        }
        if (version != VERSION) {
            throw new HandshakeException(HandshakeException.Reason.INCOMPATIBLE_VERSION,
                    "peer version " + version + ", local version " + VERSION);
        }
        return kind;
    }

    static void requireLength(byte[] message, int expected) throws HandshakeException {
        if (message.length != expected) {
            throw new HandshakeException(HandshakeException.Reason.PROTOCOL_ERROR,
                    "unexpected handshake message length " + message.length);
        }
    }

    static byte[] slice(byte[] message, int offset, int length) {
        return Arrays.copyOfRange(message, offset, offset + length);
    }
}
