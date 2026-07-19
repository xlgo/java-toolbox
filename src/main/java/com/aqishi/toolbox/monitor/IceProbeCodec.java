package com.aqishi.toolbox.monitor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Small authenticated STUN codec for peer connectivity checks.
 *
 * <p>Using STUN Binding checks makes the direct probe look like the ICE traffic
 * emitted by browser WebRTC instead of an arbitrary text datagram. Transaction
 * IDs and MESSAGE-INTEGRITY also prevent delayed public-STUN responses from
 * being mistaken for a peer handshake.</p>
 */
final class IceProbeCodec {

    static final int BINDING_REQUEST = 0x0001;
    static final int BINDING_SUCCESS = 0x0101;

    private static final int MAGIC_COOKIE = 0x2112A442;
    private static final int ATTR_USERNAME = 0x0006;
    private static final int ATTR_MESSAGE_INTEGRITY = 0x0008;
    private static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;
    private static final int ATTR_PRIORITY = 0x0024;
    private static final int ATTR_USE_CANDIDATE = 0x0025;
    private static final int ATTR_SOFTWARE = 0x8022;
    private static final int ATTR_ICE_CONTROLLING = 0x802A;
    private static final int ATTR_FINGERPRINT = 0x8028;
    private static final int FINGERPRINT_XOR = 0x5354554e;
    private static final byte[] INTEGRITY_KEY =
            "java-toolbox-direct/5".getBytes(StandardCharsets.UTF_8);
    private static final byte[] USERNAME =
            "java-toolbox:direct5".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SOFTWARE =
            "java-toolbox-ice/5".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private IceProbeCodec() {
    }

    static byte[] newTransactionId() {
        byte[] transactionId = new byte[12];
        RANDOM.nextBytes(transactionId);
        return transactionId;
    }

    static byte[] createBindingRequest(byte[] transactionId) {
        ByteBuffer buffer = startMessage(BINDING_REQUEST, transactionId);
        putAttribute(buffer, ATTR_USERNAME, USERNAME);
        putIntAttribute(buffer, ATTR_PRIORITY, 1_845_501_695);
        putLongAttribute(buffer, ATTR_ICE_CONTROLLING, RANDOM.nextLong());
        putAttribute(buffer, ATTR_USE_CANDIDATE, new byte[0]);
        putAttribute(buffer, ATTR_SOFTWARE, SOFTWARE);
        return finishAuthenticatedMessage(buffer);
    }

    static byte[] createBindingSuccess(byte[] transactionId, InetSocketAddress peerAddress) {
        if (peerAddress == null || !(peerAddress.getAddress() instanceof Inet4Address)) {
            throw new IllegalArgumentException("Only IPv4 ICE probes are supported");
        }
        ByteBuffer buffer = startMessage(BINDING_SUCCESS, transactionId);
        byte[] ip = peerAddress.getAddress().getAddress();
        int rawIp = ((ip[0] & 0xff) << 24)
                | ((ip[1] & 0xff) << 16)
                | ((ip[2] & 0xff) << 8)
                | (ip[3] & 0xff);
        ByteBuffer mapped = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        mapped.put((byte) 0);
        mapped.put((byte) 1);
        mapped.putShort((short) (peerAddress.getPort() ^ (MAGIC_COOKIE >>> 16)));
        mapped.putInt(rawIp ^ MAGIC_COOKIE);
        putAttribute(buffer, ATTR_XOR_MAPPED_ADDRESS, mapped.array());
        putAttribute(buffer, ATTR_SOFTWARE, SOFTWARE);
        return finishAuthenticatedMessage(buffer);
    }

    static ParsedMessage parse(byte[] packet, int offset, int length) {
        if (packet == null || offset < 0 || length < 20 || offset + length > packet.length) {
            return null;
        }
        byte[] message = Arrays.copyOfRange(packet, offset, offset + length);
        ByteBuffer header = ByteBuffer.wrap(message).order(ByteOrder.BIG_ENDIAN);
        int type = header.getShort() & 0xffff;
        int bodyLength = header.getShort() & 0xffff;
        if ((type != BINDING_REQUEST && type != BINDING_SUCCESS)
                || header.getInt() != MAGIC_COOKIE
                || bodyLength + 20 > message.length
                || bodyLength % 4 != 0) {
            return null;
        }
        int messageLength = bodyLength + 20;
        byte[] transactionId = Arrays.copyOfRange(message, 8, 20);
        int cursor = 20;
        int integrityOffset = -1;
        byte[] receivedIntegrity = null;
        int fingerprintOffset = -1;
        int receivedFingerprint = 0;

        while (cursor + 4 <= messageLength) {
            int attrType = unsignedShort(message, cursor);
            int attrLength = unsignedShort(message, cursor + 2);
            int valueOffset = cursor + 4;
            if (valueOffset + attrLength > messageLength) return null;
            if (attrType == ATTR_MESSAGE_INTEGRITY && attrLength == 20) {
                integrityOffset = cursor;
                receivedIntegrity = Arrays.copyOfRange(
                        message, valueOffset, valueOffset + attrLength);
            } else if (attrType == ATTR_FINGERPRINT && attrLength == 4) {
                fingerprintOffset = cursor;
                receivedFingerprint = ByteBuffer.wrap(message, valueOffset, 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .getInt();
            }
            cursor = valueOffset + ((attrLength + 3) & ~3);
        }

        if (integrityOffset < 0 || receivedIntegrity == null || fingerprintOffset < 0) {
            return null;
        }
        if (!verifyIntegrity(message, integrityOffset, receivedIntegrity)
                || !verifyFingerprint(message, fingerprintOffset, receivedFingerprint)) {
            return null;
        }
        return new ParsedMessage(type, transactionId);
    }

    static String transactionKey(byte[] transactionId) {
        if (transactionId == null) return "";
        StringBuilder key = new StringBuilder(transactionId.length * 2);
        for (byte value : transactionId) {
            key.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            key.append(Character.forDigit(value & 0x0f, 16));
        }
        return key.toString();
    }

    private static ByteBuffer startMessage(int type, byte[] transactionId) {
        if (transactionId == null || transactionId.length != 12) {
            throw new IllegalArgumentException("STUN transaction ID must contain 12 bytes");
        }
        ByteBuffer buffer = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN);
        buffer.putShort((short) type);
        buffer.putShort((short) 0);
        buffer.putInt(MAGIC_COOKIE);
        buffer.put(transactionId);
        return buffer;
    }

    private static byte[] finishAuthenticatedMessage(ByteBuffer buffer) {
        int integrityOffset = buffer.position();
        buffer.putShort((short) ATTR_MESSAGE_INTEGRITY);
        buffer.putShort((short) 20);
        int integrityValueOffset = buffer.position();
        buffer.put(new byte[20]);

        int lengthThroughIntegrity = buffer.position() - 20;
        buffer.putShort(2, (short) lengthThroughIntegrity);
        byte[] hmacInput = Arrays.copyOf(buffer.array(), integrityOffset);
        byte[] integrity = hmacSha1(hmacInput);
        System.arraycopy(integrity, 0, buffer.array(), integrityValueOffset, integrity.length);

        int fingerprintOffset = buffer.position();
        buffer.putShort((short) ATTR_FINGERPRINT);
        buffer.putShort((short) 4);
        int fingerprintValueOffset = buffer.position();
        buffer.putInt(0);
        buffer.putShort(2, (short) (buffer.position() - 20));

        CRC32 crc = new CRC32();
        crc.update(buffer.array(), 0, fingerprintOffset);
        buffer.putInt(fingerprintValueOffset, (int) crc.getValue() ^ FINGERPRINT_XOR);
        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    private static boolean verifyIntegrity(byte[] message,
                                           int integrityOffset,
                                           byte[] receivedIntegrity) {
        byte[] input = Arrays.copyOf(message, integrityOffset);
        int lengthThroughIntegrity = integrityOffset - 20 + 24;
        input[2] = (byte) (lengthThroughIntegrity >>> 8);
        input[3] = (byte) lengthThroughIntegrity;
        return MessageDigest.isEqual(receivedIntegrity, hmacSha1(input));
    }

    private static boolean verifyFingerprint(byte[] message,
                                             int fingerprintOffset,
                                             int receivedFingerprint) {
        CRC32 crc = new CRC32();
        crc.update(message, 0, fingerprintOffset);
        return ((int) crc.getValue() ^ FINGERPRINT_XOR) == receivedFingerprint;
    }

    private static byte[] hmacSha1(byte[] input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(INTEGRITY_KEY, "HmacSHA1"));
            return mac.doFinal(input);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA1 is unavailable", e);
        }
    }

    private static void putIntAttribute(ByteBuffer buffer, int type, int value) {
        ByteBuffer bytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        bytes.putInt(value);
        putAttribute(buffer, type, bytes.array());
    }

    private static void putLongAttribute(ByteBuffer buffer, int type, long value) {
        ByteBuffer bytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        bytes.putLong(value);
        putAttribute(buffer, type, bytes.array());
    }

    private static void putAttribute(ByteBuffer buffer, int type, byte[] value) {
        buffer.putShort((short) type);
        buffer.putShort((short) value.length);
        buffer.put(value);
        while ((buffer.position() & 3) != 0) buffer.put((byte) 0);
    }

    private static int unsignedShort(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    static final class ParsedMessage {
        private final int type;
        private final byte[] transactionId;

        private ParsedMessage(int type, byte[] transactionId) {
            this.type = type;
            this.transactionId = transactionId;
        }

        int getType() {
            return type;
        }

        byte[] getTransactionId() {
            return Arrays.copyOf(transactionId, transactionId.length);
        }
    }
}
