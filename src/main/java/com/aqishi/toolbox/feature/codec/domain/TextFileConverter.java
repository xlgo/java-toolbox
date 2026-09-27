package com.aqishi.toolbox.feature.codec.domain;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * 文本字节的编码转换、换行符规范化与 BOM 处理（纯函数，字节进字节出，不碰文件系统）。
 *
 * <p>解码与编码都用 {@link CodingErrorAction#REPORT}：批量转换最怕的不是报错，而是「静默替换成 ?」
 * 之后又覆盖了原文件。解码失败报告字节偏移，编码失败报告第一个无法映射的字符及其行号，
 * 只有用户明确选择「替换为 ?」时才会有损输出。</p>
 */
public final class TextFileConverter {

    private static final byte[] BOM_UTF8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] BOM_UTF16LE = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] BOM_UTF16BE = {(byte) 0xFE, (byte) 0xFF};
    private static final byte[] BOM_UTF32LE = {(byte) 0xFF, (byte) 0xFE, 0, 0};
    private static final byte[] BOM_UTF32BE = {0, 0, (byte) 0xFE, (byte) 0xFF};
    private static final byte[] NO_BOM = new byte[0];

    /** BOM 处理方式。KEEP 表示「源文件有就保留、没有就不加」（目标不是 Unicode 编码时一律不写 BOM）。 */
    public enum BomMode { KEEP, ADD, REMOVE }

    /** 目标换行符。 */
    public enum LineEnding { KEEP, LF, CRLF, CR }

    /** 转换结论。 */
    public enum Status { CONVERTED, UNCHANGED, FAILED }

    /** 失败原因。 */
    public enum Failure { NONE, MALFORMED_INPUT, UNMAPPABLE_CHARACTER }

    /**
     * 转换选项。
     *
     * @param target             目标编码，null 表示沿用源编码（只调整 BOM / 换行）
     * @param replaceUnmappable  目标编码无法表示的字符替换为 {@code ?}，而不是报错
     */
    public record Options(Charset target, BomMode bom, LineEnding lineEnding, boolean replaceUnmappable) {
        public Options {
            Objects.requireNonNull(bom, "bom");
            Objects.requireNonNull(lineEnding, "lineEnding");
        }

        public static Options utf8() {
            return new Options(StandardCharsets.UTF_8, BomMode.REMOVE, LineEnding.KEEP, false);
        }
    }

    /** 文本里的换行符统计。 */
    public record LineStats(int crlf, int lf, int cr) {

        public enum Style { NONE, LF, CRLF, CR, MIXED }

        public static LineStats of(CharSequence text) {
            int crlf = 0;
            int lf = 0;
            int cr = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\r') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        crlf++;
                        i++;
                    } else {
                        cr++;
                    }
                } else if (c == '\n') {
                    lf++;
                }
            }
            return new LineStats(crlf, lf, cr);
        }

        public Style style() {
            int kinds = (crlf > 0 ? 1 : 0) + (lf > 0 ? 1 : 0) + (cr > 0 ? 1 : 0);
            if (kinds == 0) {
                return Style.NONE;
            }
            if (kinds > 1) {
                return Style.MIXED;
            }
            return crlf > 0 ? Style.CRLF : lf > 0 ? Style.LF : Style.CR;
        }

        public int total() {
            return crlf + lf + cr;
        }
    }

    /**
     * 严格解码结果。
     *
     * @param text        解码后的文本（不含 BOM）；失败时为 null
     * @param bomLength   跳过的 BOM 字节数
     * @param errorOffset 第一个非法字节在原始字节中的偏移，成功时为 -1
     * @param errorLine   出错位置所在行（从 1 开始），成功时为 0
     */
    public record Decoded(String text, int bomLength, long errorOffset, int errorLine) {
        public boolean ok() {
            return text != null;
        }
    }

    /**
     * 转换结果。
     *
     * @param output         目标字节；失败时为 null
     * @param sourceBom      源字节是否带 BOM
     * @param targetBom      输出是否带 BOM
     * @param found          源文本中的换行统计；解码失败时为 null
     * @param errorOffset    解码失败：非法字节偏移；编码失败：字符在文本中的下标。否则为 -1
     * @param errorLine      出错所在行（从 1 开始），否则 0
     * @param errorCodePoint 无法映射的字符码点，否则 -1
     * @param replacements   选择替换时被替换成 ? 的字符数
     */
    public record Result(Status status, byte[] output, Charset source, Charset target,
                         boolean sourceBom, boolean targetBom, LineStats found, Failure failure,
                         long errorOffset, int errorLine, int errorCodePoint, int replacements) {
    }

    private TextFileConverter() {
    }

    /** 该编码写文件时使用的 BOM；非 Unicode 编码返回空数组。 */
    public static byte[] bomFor(Charset charset) {
        String name = charset.name();
        switch (name) {
            case "UTF-8":
                return BOM_UTF8.clone();
            case "UTF-16LE":
                return BOM_UTF16LE.clone();
            case "UTF-16BE":
            case "UTF-16":
                return BOM_UTF16BE.clone();
            case "UTF-32LE":
                return BOM_UTF32LE.clone();
            case "UTF-32BE":
            case "UTF-32":
                return BOM_UTF32BE.clone();
            default:
                return NO_BOM;
        }
    }

    /** 字节开头是否就是该编码的 BOM，返回其长度（0 表示没有）。 */
    public static int bomLength(byte[] data, Charset charset) {
        String name = charset.name();
        if ("UTF-16".equals(name)) {
            return startsWith(data, BOM_UTF16BE) || startsWith(data, BOM_UTF16LE) ? 2 : 0;
        }
        if ("UTF-32".equals(name)) {
            return startsWith(data, BOM_UTF32BE) || startsWith(data, BOM_UTF32LE) ? 4 : 0;
        }
        byte[] bom = bomFor(charset);
        return bom.length > 0 && startsWith(data, bom) ? bom.length : 0;
    }

    /**
     * 按指定编码严格解码；开头若是该编码的 BOM 则跳过。
     */
    public static Decoded decode(byte[] data, Charset source) {
        Objects.requireNonNull(data, "data");
        Charset charset = normalize(Objects.requireNonNull(source, "source"), data);
        int bom = bomLength(data, charset);
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(data, bom, data.length - bom);
        CharBuffer out = CharBuffer.allocate(Math.max(16, (int) Math.min(Integer.MAX_VALUE - 16L,
                (long) ((data.length - bom) * (double) decoder.averageCharsPerByte()) + 16)));
        CoderResult result;
        while ((result = decoder.decode(in, out, true)).isOverflow()) {
            out = grow(out);
        }
        if (result.isError()) {
            // 解码器在报错时 position 停在非法序列的起点；wrap 带偏移，position 即原始字节中的绝对偏移
            out.flip();
            return new Decoded(null, bom, in.position(), lineAt(out, out.limit()));
        }
        while (decoder.flush(out).isOverflow()) {
            out = grow(out);
        }
        out.flip();
        return new Decoded(out.toString(), bom, -1, 0);
    }

    /**
     * 转换一段文件字节。
     */
    public static Result convert(byte[] data, Charset source, Options options) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(options, "options");
        Charset sourceCharset = normalize(Objects.requireNonNull(source, "source"), data);
        Charset target = options.target() == null ? sourceCharset : options.target();
        if ("UTF-16".equals(target.name())) {
            target = StandardCharsets.UTF_16BE;
        } else if ("UTF-32".equals(target.name())) {
            Charset be = EncodingDetector.lookup("UTF-32BE");
            target = be != null ? be : target;
        }

        Decoded decoded = decode(data, sourceCharset);
        boolean sourceBom = decoded.bomLength() > 0;
        if (!decoded.ok()) {
            return new Result(Status.FAILED, null, sourceCharset, target, sourceBom, false, null,
                    Failure.MALFORMED_INPUT, decoded.errorOffset(), decoded.errorLine(), -1, 0);
        }
        String text = decoded.text();
        LineStats found = LineStats.of(text);
        String normalized = normalizeLineEndings(text, options.lineEnding());

        byte[] bom = bomFor(target);
        boolean writeBom;
        switch (options.bom()) {
            case ADD:
                writeBom = bom.length > 0;
                break;
            case REMOVE:
                writeBom = false;
                break;
            default:
                writeBom = sourceBom && bom.length > 0;
                break;
        }

        CharsetEncoder encoder = target.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        byte[] replacement = replacementBytes(target);
        CharBuffer in = CharBuffer.wrap(normalized);
        ByteBuffer out = ByteBuffer.allocate(Math.max(16, (int) Math.min(Integer.MAX_VALUE - 16L,
                (long) (normalized.length() * (double) encoder.averageBytesPerChar()) + bom.length + 16)));
        if (writeBom) {
            out.put(bom);
        }
        int replacements = 0;
        while (true) {
            CoderResult result = encoder.encode(in, out, true);
            if (result.isUnderflow()) {
                break;
            }
            if (result.isOverflow()) {
                out = grow(out, Math.max(out.capacity(), replacement.length + 16));
                continue;
            }
            int index = in.position();
            if (options.replaceUnmappable() && result.isUnmappable()) {
                if (out.remaining() < replacement.length) {
                    out = grow(out, out.capacity() + replacement.length);
                }
                out.put(replacement);
                in.position(index + result.length());
                replacements++;
                continue;
            }
            int codePoint = index < normalized.length() ? normalized.codePointAt(index) : -1;
            return new Result(Status.FAILED, null, sourceCharset, target, sourceBom, writeBom, found,
                    Failure.UNMAPPABLE_CHARACTER, index, lineAt(normalized, index), codePoint, 0);
        }
        while (encoder.flush(out).isOverflow()) {
            out = grow(out, out.capacity());
        }
        byte[] output = Arrays.copyOf(out.array(), out.position());
        Status status = Arrays.equals(output, data) ? Status.UNCHANGED : Status.CONVERTED;
        return new Result(status, output, sourceCharset, target, sourceBom, writeBom, found,
                Failure.NONE, -1, 0, -1, replacements);
    }

    /** 统一换行符；KEEP 原样返回。 */
    public static String normalizeLineEndings(String text, LineEnding ending) {
        if (ending == LineEnding.KEEP || text.indexOf('\r') < 0 && ending == LineEnding.LF) {
            return text;
        }
        String separator = ending == LineEnding.LF ? "\n" : ending == LineEnding.CRLF ? "\r\n" : "\r";
        StringBuilder builder = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                builder.append(separator);
            } else if (c == '\n') {
                builder.append(separator);
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    /** 下标所在行号（从 1 开始），CRLF 记一次换行，单独的 CR 也算换行。 */
    static int lineAt(CharSequence text, int end) {
        int line = 1;
        int limit = Math.min(end, text.length());
        for (int i = 0; i < limit; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
            } else if (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n')) {
                line++;
            }
        }
        return line;
    }

    /** 通用的 UTF-16/UTF-32 按 BOM 落到具体字节序，写 BOM 与剥 BOM 才有确定的语义。 */
    private static Charset normalize(Charset charset, byte[] data) {
        String name = charset.name();
        if ("UTF-16".equals(name)) {
            return startsWith(data, BOM_UTF16LE) ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE;
        }
        if ("UTF-32".equals(name)) {
            Charset resolved = EncodingDetector.lookup(startsWith(data, BOM_UTF32LE) ? "UTF-32LE" : "UTF-32BE");
            return resolved != null ? resolved : charset;
        }
        return charset;
    }

    private static byte[] replacementBytes(Charset target) {
        byte[] bytes = "?".getBytes(target);
        return bytes.length == 0 ? new byte[]{'?'} : bytes;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static CharBuffer grow(CharBuffer buffer) {
        CharBuffer bigger = CharBuffer.allocate(Math.max(16, buffer.capacity() * 2));
        buffer.flip();
        bigger.put(buffer);
        return bigger;
    }

    private static ByteBuffer grow(ByteBuffer buffer, int extra) {
        ByteBuffer bigger = ByteBuffer.allocate(buffer.capacity() + Math.max(16, extra));
        buffer.flip();
        bigger.put(buffer);
        return bigger;
    }
}
