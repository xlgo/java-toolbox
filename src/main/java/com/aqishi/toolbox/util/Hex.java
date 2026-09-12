package com.aqishi.toolbox.util;

/**
 * 字节数组与十六进制字符串互转的唯一入口。
 *
 * <p>此前 MD5/SHA/SM3/签名等工具各自在字节循环里调用
 * {@code String.format("%02x", b & 0xff)}：每次格式化都要解析格式串、走一遍
 * Formatter，放在逐字节循环里开销明显。这里统一改为查表法
 * {@code HEX[b & 0xff]}，并复用同一份字符表，快约一个数量级。</p>
 */
public final class Hex {

    private static final char[] LOWER = "0123456789abcdef".toCharArray();
    private static final char[] UPPER = "0123456789ABCDEF".toCharArray();

    private Hex() {
    }

    /** 转为小写十六进制串，每字节两位。 */
    public static String toHex(byte[] bytes) {
        return toHex(bytes, 0, bytes.length);
    }

    /** 转为小写十六进制串，只取 {@code [offset, offset + length)} 区间。 */
    public static String toHex(byte[] bytes, int offset, int length) {
        return encode(bytes, offset, length, LOWER);
    }

    /** 转为大写十六进制串，每字节两位，用于 URL 百分号编码等要求大写的场景。 */
    public static String toHexUpper(byte[] bytes) {
        return toHexUpper(bytes, 0, bytes.length);
    }

    /** 转为大写十六进制串，只取 {@code [offset, offset + length)} 区间。 */
    public static String toHexUpper(byte[] bytes, int offset, int length) {
        return encode(bytes, offset, length, UPPER);
    }

    private static String encode(byte[] bytes, int offset, int length, char[] table) {
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            throw new IndexOutOfBoundsException(
                    "offset=" + offset + ", length=" + length + ", size=" + bytes.length);
        }
        char[] out = new char[length * 2];
        for (int i = 0; i < length; i++) {
            int value = bytes[offset + i] & 0xff;
            out[i * 2] = table[value >>> 4];
            out[i * 2 + 1] = table[value & 0x0f];
        }
        return new String(out);
    }
}
