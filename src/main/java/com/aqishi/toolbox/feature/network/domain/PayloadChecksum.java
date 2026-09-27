package com.aqishi.toolbox.feature.network.domain;

import java.util.Locale;
import java.util.zip.CRC32;

/**
 * 追加在载荷末尾的校验值。
 *
 * <p>字节序跟随各协议的事实标准：CRC-16/MODBUS 低字节在前（Modbus RTU 帧就是这样），
 * 其余多字节校验按大端追加。参数均取自 CRC RevEng 目录，"123456789" 的校验值见单元测试。</p>
 */
public enum PayloadChecksum {
    NONE(0),
    /** 所有字节求和取低 8 位。 */
    SUM8(1),
    /** 所有字节异或。 */
    XOR8(1),
    /** CRC-8：poly 0x07，init 0x00，不反射，xorout 0x00。 */
    CRC8(1),
    /** CRC-16/MODBUS：poly 0x8005（反射 0xA001），init 0xFFFF，低字节在前追加。 */
    CRC16_MODBUS(2),
    /** CRC-16/CCITT-FALSE：poly 0x1021，init 0xFFFF，不反射，大端追加。 */
    CRC16_CCITT_FALSE(2),
    /** CRC-32（IEEE 802.3 / zip），大端追加。 */
    CRC32(4);

    private final int width;

    PayloadChecksum(int width) {
        this.width = width;
    }

    /** 校验值占用的字节数，NONE 为 0。 */
    public int width() {
        return width;
    }

    /** 计算校验值（无符号，放在 long 低位）。 */
    public long compute(byte[] data) {
        switch (this) {
            case SUM8:
                return sum8(data);
            case XOR8:
                return xor8(data);
            case CRC8:
                return crc8(data);
            case CRC16_MODBUS:
                return crc16Modbus(data);
            case CRC16_CCITT_FALSE:
                return crc16CcittFalse(data);
            case CRC32:
                CRC32 crc = new CRC32();
                crc.update(data, 0, data.length);
                return crc.getValue();
            default:
                return 0L;
        }
    }

    /** 校验值按本算法约定的字节序展开成要追加的字节。 */
    public byte[] encode(long value) {
        byte[] out = new byte[width];
        if (this == CRC16_MODBUS) {
            out[0] = (byte) value;
            out[1] = (byte) (value >>> 8);
            return out;
        }
        for (int i = 0; i < width; i++) {
            out[i] = (byte) (value >>> (8 * (width - 1 - i)));
        }
        return out;
    }

    /** 返回 data 后接校验字节的新数组；NONE 时返回副本。 */
    public byte[] append(byte[] data) {
        byte[] tail = encode(compute(data));
        byte[] out = new byte[data.length + tail.length];
        System.arraycopy(data, 0, out, 0, data.length);
        System.arraycopy(tail, 0, out, data.length, tail.length);
        return out;
    }

    /** 以 0x 前缀、按宽度补零的大写十六进制显示校验值，例如 0x4B37。 */
    public String format(long value) {
        if (width == 0) {
            return "";
        }
        String hex = Long.toHexString(value).toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder("0x");
        for (int i = hex.length(); i < width * 2; i++) {
            out.append('0');
        }
        return out.append(hex).toString();
    }

    private static long sum8(byte[] data) {
        int sum = 0;
        for (byte b : data) {
            sum += b & 0xFF;
        }
        return sum & 0xFF;
    }

    private static long xor8(byte[] data) {
        int x = 0;
        for (byte b : data) {
            x ^= b & 0xFF;
        }
        return x;
    }

    private static long crc8(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc ^= b & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
            }
        }
        return crc;
    }

    private static long crc16Modbus(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc ^= b & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
            }
        }
        return crc & 0xFFFF;
    }

    private static long crc16CcittFalse(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc ^= (b & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
        }
        return crc & 0xFFFF;
    }
}
