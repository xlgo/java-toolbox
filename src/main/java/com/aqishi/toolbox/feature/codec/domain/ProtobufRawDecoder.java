package com.aqishi.toolbox.feature.codec.domain;

import com.aqishi.toolbox.util.I18n;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 纯离线 Protobuf 原始二进制解码器（无需 .proto 定义文件）。
 *
 * <p>兼容 Hex 与 Base64 输入，完整支持 WireType 0 (Varint), 1 (Fixed64),
 * 2 (Length-delimited), 5 (Fixed32)；对长度界定数据进行启发式 Message 递归识别、
 * UTF-8 字符串检测与原始字节展示。</p>
 */
public final class ProtobufRawDecoder {

    public enum WireType {
        VARINT(0),
        FIXED64(1),
        LENGTH_DELIMITED(2),
        START_GROUP(3),
        END_GROUP(4),
        FIXED32(5);

        private final int value;

        WireType(int value) {
            this.value = value;
        }

        public static WireType fromValue(int val) {
            for (WireType wt : values()) {
                if (wt.value == val) return wt;
            }
            return null;
        }
    }

    public sealed interface ProtoValue permits VarintValue, Fixed64Value, Fixed32Value, BytesValue, MessageValue {}

    public record VarintValue(long rawValue, long zigzagValue, boolean asBool) implements ProtoValue {}
    public record Fixed64Value(long rawBits, double doubleVal, String hex) implements ProtoValue {}
    public record Fixed32Value(int rawBits, float floatVal, String hex) implements ProtoValue {}
    public record BytesValue(byte[] bytes, String asUtf8String, List<Long> asPackedVarints) implements ProtoValue {}
    public record MessageValue(List<ProtoField> fields) implements ProtoValue {}

    public record ProtoField(int fieldNumber, WireType wireType, ProtoValue value) {}

    public record DecodeReport(List<ProtoField> rootFields, int byteCount, int fieldCount, int maxDepth) {}

    private static final int MAX_DEPTH = 16;
    private static final int MAX_INPUT_BYTES = 5 * 1024 * 1024; // 5 MiB

    public String decode(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException(I18n.get("proto.empty"));
        }
        if (input.length() > 2_000_000) {
            throw new IllegalArgumentException(I18n.get("devtools.inputLimit"));
        }

        byte[] data = parseInputBytes(input);
        if (data.length == 0) {
            throw new IllegalArgumentException(I18n.get("proto.empty"));
        }
        if (data.length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException(I18n.get("proto.byteLimit", MAX_INPUT_BYTES));
        }

        DecodeReport report = decodeBytes(data);
        if (report.rootFields().isEmpty()) {
            throw new IllegalArgumentException(I18n.get("proto.noFields"));
        }

        StringBuilder out = new StringBuilder();
        out.append(I18n.get("proto.summary", report.byteCount(), report.fieldCount(), report.maxDepth())).append("\n\n");
        formatFields(out, report.rootFields(), 0);
        return out.toString().trim();
    }

    public DecodeReport decodeBytes(byte[] data) {
        List<ProtoField> fields = parseMessage(data, 0, data.length, 1);
        int[] stats = countStats(fields, 1);
        return new DecodeReport(fields, data.length, stats[0], stats[1]);
    }

    private static int[] countStats(List<ProtoField> fields, int currentDepth) {
        int count = fields.size();
        int maxD = currentDepth;
        for (ProtoField f : fields) {
            if (f.value() instanceof MessageValue mv) {
                int[] sub = countStats(mv.fields(), currentDepth + 1);
                count += sub[0];
                maxD = Math.max(maxD, sub[1]);
            }
        }
        return new int[]{count, maxD};
    }

    private List<ProtoField> parseMessage(byte[] data, int offset, int length, int depth) {
        if (depth > MAX_DEPTH) {
            return List.of();
        }

        ByteBuffer buf = ByteBuffer.wrap(data, offset, length);
        List<ProtoField> fields = new ArrayList<>();

        while (buf.hasRemaining()) {
            int tagPos = buf.position();
            Long tagVal = readVarint(buf);
            if (tagVal == null) break;

            int tag = (int) (long) tagVal;
            int wireVal = tag & 0x07;
            int fieldNum = tag >>> 3;

            if (fieldNum <= 0) {
                // 非法 field number，视为解析中断
                break;
            }

            WireType wireType = WireType.fromValue(wireVal);
            if (wireType == null) {
                break;
            }

            switch (wireType) {
                case VARINT -> {
                    Long v = readVarint(buf);
                    if (v == null) return fields;
                    long val = v;
                    long zigzag = (val >>> 1) ^ -(val & 1);
                    boolean asBool = val == 1;
                    fields.add(new ProtoField(fieldNum, wireType, new VarintValue(val, zigzag, asBool)));
                }
                case FIXED64 -> {
                    if (buf.remaining() < 8) return fields;
                    long raw = buf.order(ByteOrder.LITTLE_ENDIAN).getLong();
                    double d = Double.longBitsToDouble(raw);
                    String hex = String.format("0x%016x", raw);
                    fields.add(new ProtoField(fieldNum, wireType, new Fixed64Value(raw, d, hex)));
                }
                case FIXED32 -> {
                    if (buf.remaining() < 4) return fields;
                    int raw = buf.order(ByteOrder.LITTLE_ENDIAN).getInt();
                    float f = Float.intBitsToFloat(raw);
                    String hex = String.format("0x%08x", raw);
                    fields.add(new ProtoField(fieldNum, wireType, new Fixed32Value(raw, f, hex)));
                }
                case LENGTH_DELIMITED -> {
                    Long lenVal = readVarint(buf);
                    if (lenVal == null || lenVal < 0 || lenVal > buf.remaining()) {
                        return fields;
                    }
                    int len = lenVal.intValue();
                    byte[] slice = new byte[len];
                    buf.get(slice);

                    ProtoValue resolved = resolveLengthDelimited(slice, depth);
                    fields.add(new ProtoField(fieldNum, wireType, resolved));
                }
                case START_GROUP, END_GROUP -> {
                    // 分组已废弃，直接停止
                    return fields;
                }
            }
        }
        return fields;
    }

    private ProtoValue resolveLengthDelimited(byte[] slice, int depth) {
        if (slice.length == 0) {
            return new BytesValue(slice, "", List.of());
        }

        // 1. 启发式尝试作为嵌套 Message
        if (depth < MAX_DEPTH) {
            List<ProtoField> sub = parseMessage(slice, 0, slice.length, depth + 1);
            if (isPlausibleMessage(sub, slice.length)) {
                return new MessageValue(sub);
            }
        }

        // 2. 启发式尝试作为 UTF-8 字符串
        String utf8 = tryParseUtf8String(slice);
        if (utf8 != null) {
            return new BytesValue(slice, utf8, null);
        }

        // 3. 尝试解析为 Packed Varints
        List<Long> packed = tryParsePackedVarints(slice);

        // 4. 原始字节
        return new BytesValue(slice, null, packed);
    }

    private static boolean isPlausibleMessage(List<ProtoField> fields, int sliceLength) {
        if (fields.isEmpty()) return false;
        // 如果字段数过少但字节很长，说明并非结构体
        int validNumbers = 0;
        for (ProtoField f : fields) {
            if (f.fieldNumber() >= 1 && f.fieldNumber() <= 536870911) {
                validNumbers++;
            }
        }
        return validNumbers == fields.size() && (fields.size() >= 1 || sliceLength <= 2);
    }

    private static String tryParseUtf8String(byte[] bytes) {
        if (bytes.length == 0) return "";
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            String str = decoder.decode(ByteBuffer.wrap(bytes)).toString();
            // 校验字符可读性，避免二进制字节恰好合法的偶发碰撞
            int printable = 0;
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);
                if (c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c != 0x7F)) {
                    printable++;
                }
            }
            if ((double) printable / str.length() >= 0.95) {
                return str;
            }
        } catch (CharacterCodingException ignored) {
        }
        return null;
    }

    private static List<Long> tryParsePackedVarints(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        List<Long> list = new ArrayList<>();
        while (buf.hasRemaining() && list.size() < 100) {
            Long val = readVarint(buf);
            if (val == null) return null;
            list.add(val);
        }
        return (!buf.hasRemaining() && list.size() > 1) ? list : null;
    }

    private static Long readVarint(ByteBuffer buf) {
        long result = 0;
        int shift = 0;
        while (buf.hasRemaining()) {
            byte b = buf.get();
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 63) {
                return null; // 超过 10 字节溢出
            }
        }
        return null;
    }

    private void formatFields(StringBuilder sb, List<ProtoField> fields, int indentLevel) {
        String pad = "  ".repeat(indentLevel);
        for (ProtoField field : fields) {
            sb.append(pad).append(field.fieldNumber()).append(": ");
            ProtoValue val = field.value();
            if (val instanceof VarintValue vi) {
                sb.append(vi.rawValue()).append(" (varint");
                if (vi.zigzagValue() != vi.rawValue()) {
                    sb.append(", zigzag: ").append(vi.zigzagValue());
                }
                if (vi.rawValue() == 0 || vi.rawValue() == 1) {
                    sb.append(", bool: ").append(vi.asBool());
                }
                sb.append(")\n");
            } else if (val instanceof Fixed64Value f64) {
                sb.append(f64.doubleVal()).append(" (fixed64: ").append(f64.hex())
                        .append(", raw: ").append(f64.rawBits()).append(")\n");
            } else if (val instanceof Fixed32Value f32) {
                sb.append(f32.floatVal()).append(" (fixed32: ").append(f32.hex())
                        .append(", raw: ").append(f32.rawBits()).append(")\n");
            } else if (val instanceof BytesValue bv) {
                if (bv.asUtf8String() != null) {
                    sb.append("\"").append(escapeString(bv.asUtf8String())).append("\" (string, ")
                            .append(bv.bytes().length).append(" bytes)\n");
                } else if (bv.asPackedVarints() != null) {
                    sb.append(bv.asPackedVarints()).append(" (packed varints, ")
                            .append(bv.bytes().length).append(" bytes)\n");
                } else {
                    sb.append(formatHexSlice(bv.bytes())).append(" (bytes, ")
                            .append(bv.bytes().length).append(" bytes)\n");
                }
            } else if (val instanceof MessageValue mv) {
                sb.append("{\n");
                formatFields(sb, mv.fields(), indentLevel + 1);
                sb.append(pad).append("}\n");
            }
        }
    }

    private static String formatHexSlice(byte[] bytes) {
        if (bytes.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        int limit = Math.min(bytes.length, 32);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(" ");
            sb.append(String.format("%02x", bytes[i]));
        }
        if (bytes.length > limit) {
            sb.append(" ... ").append(bytes.length - limit).append(" more");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escapeString(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    public static byte[] parseInputBytes(String text) {
        String clean = text.trim();

        // 尝试作为 Base64 解析（不含空格换行，包含合法 Base64 字符且长度被 4 整除或以 = 结尾）
        String noSpace = clean.replaceAll("\\s+", "");
        boolean looksLikeBase64 = noSpace.length() >= 4 && (noSpace.contains("=") || noSpace.contains("+") || noSpace.contains("/")
                || (noSpace.matches("^[A-Za-z0-9+/=]+$") && !noSpace.matches("^[0-9a-fA-F]+$")));

        if (looksLikeBase64) {
            try {
                return Base64.getDecoder().decode(noSpace);
            } catch (IllegalArgumentException ignored) {
            }
        }

        // 尝试作为 Hex 解析（去除 0x、空格、冒号、逗号）
        String hexClean = clean.replaceAll("0x|0X|[\\s,:]+", "");
        if (hexClean.matches("^[0-9a-fA-F]+$") && hexClean.length() % 2 == 0) {
            int len = hexClean.length();
            byte[] data = new byte[len / 2];
            for (int i = 0; i < len; i += 2) {
                data[i / 2] = (byte) ((Character.digit(hexClean.charAt(i), 16) << 4)
                        + Character.digit(hexClean.charAt(i + 1), 16));
            }
            return data;
        }

        // 最终回退尝试 Base64
        try {
            return Base64.getDecoder().decode(noSpace);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(I18n.get("proto.invalidEncoding"));
        }
    }
}
