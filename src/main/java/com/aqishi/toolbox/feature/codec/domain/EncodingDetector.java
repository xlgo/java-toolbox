package com.aqishi.toolbox.feature.codec.domain;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 文本文件编码探测（纯函数，输入字节，输出排好序的候选）。
 *
 * <p>判定顺序与各自的可信度来源：</p>
 * <ol>
 *     <li>BOM：最硬的证据，直接采信；</li>
 *     <li>NUL 字节：要么是 UTF-16（奇/偶位置规律分布），要么是二进制；</li>
 *     <li>纯 ASCII：与 UTF-8/GBK 都兼容，转换只影响 BOM 与换行；</li>
 *     <li>严格 UTF-8：多字节序列能完整通过校验几乎不可能是巧合，因此一旦有效就优先；</li>
 *     <li>GBK/GB18030、Big5、Shift_JIS：结构校验 + 按码位分区打「常用度」分，互相比较；</li>
 *     <li>ISO-8859-1 / windows-1252：任何字节都能解码，只作兜底。</li>
 * </ol>
 *
 * <p>多字节编码之间天然重叠（大段 GBK 文本同时也是合法的 Big5），所以返回的是带置信度的候选列表，
 * 而不是一个武断的答案；置信度同时考虑了证据量（字符越少越不可信）和第二名的接近程度。</p>
 */
public final class EncodingDetector {

    /** 探测只看前这么多字节；更长的内容交给严格解码去发现问题。 */
    public static final int DEFAULT_SAMPLE_BYTES = 1 << 20;

    /** 判定依据。界面按它映射文案，领域层不产生任何面向用户的文字。 */
    public enum Reason {
        /** 空文件。 */
        EMPTY,
        /** 由 BOM 确定。 */
        BOM,
        /** BOM 存在但正文不能按该编码严格解码。 */
        BOM_INVALID_BODY,
        /** 全部字节小于 0x80。 */
        ASCII,
        /** 严格 UTF-8 校验通过且含多字节序列。 */
        UTF8_VALID,
        /** 除末尾一个不完整的序列外都是合法 UTF-8：文件多半被截断了。 */
        UTF8_TRUNCATED_TAIL,
        /** NUL 字节集中在奇数或偶数位置，符合无 BOM 的 UTF-16。 */
        UTF16_NUL_PATTERN,
        /** 没有 NUL，但按 UTF-16 解码后几乎全是常用汉字。 */
        UTF16_TEXT_PATTERN,
        /** 双字节编码结构有效，按常用字分布打分。 */
        DOUBLE_BYTE_SCORE,
        /** 单字节西文编码兜底。 */
        SINGLE_BYTE_FALLBACK,
        /** 判定为二进制文件。 */
        BINARY
    }

    /** 一个候选编码及其置信度。 */
    public record Candidate(Charset charset, double confidence, Reason reason) {
        public Candidate {
            Objects.requireNonNull(charset, "charset");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * 探测结果：首选编码、置信度（0..1）、是否带 BOM、依据，以及完整的候选排序。
     *
     * @param charset         首选编码；二进制时为 null
     * @param bomLength       BOM 字节数，没有 BOM 时为 0
     * @param sampleTruncated 是否只分析了文件前缀
     */
    public record Result(Charset charset, double confidence, boolean bom, int bomLength, Reason reason,
                         List<Candidate> candidates, boolean sampleTruncated) {
        public Result {
            candidates = List.copyOf(candidates);
        }

        public boolean binary() {
            return reason == Reason.BINARY;
        }

        public boolean ascii() {
            return reason == Reason.ASCII || reason == Reason.EMPTY;
        }
    }

    private static final Charset UTF_32LE = lookup("UTF-32LE");
    private static final Charset UTF_32BE = lookup("UTF-32BE");
    private static final Charset GBK = lookup("GBK");
    private static final Charset GB18030 = lookup("GB18030");
    private static final Charset BIG5 = lookup("Big5");
    private static final Charset WINDOWS_31J = lookup("windows-31j");
    private static final Charset WINDOWS_1252 = lookup("windows-1252");

    private final int sampleBytes;

    public EncodingDetector() {
        this(DEFAULT_SAMPLE_BYTES);
    }

    public EncodingDetector(int sampleBytes) {
        if (sampleBytes < 16) {
            throw new IllegalArgumentException("sampleBytes must be >= 16");
        }
        this.sampleBytes = sampleBytes;
    }

    /** 按名称取编码，当前运行时不支持（例如裁剪过的 JRE 缺少 jdk.charsets）时返回 null。 */
    public static Charset lookup(String name) {
        try {
            return Charset.isSupported(name) ? Charset.forName(name) : null;
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    /** 探测完整文件内容。 */
    public Result detect(byte[] data) {
        Objects.requireNonNull(data, "data");
        return detect(data, data.length, false);
    }

    /**
     * 探测字节前缀。
     *
     * @param length    有效字节数
     * @param truncated 文件在 {@code length} 之后还有内容（只读了前缀）——此时末尾被切断的多字节序列不算错误
     */
    public Result detect(byte[] data, int length, boolean truncated) {
        Objects.requireNonNull(data, "data");
        if (length < 0 || length > data.length) {
            throw new IllegalArgumentException("length out of range");
        }
        if (length > sampleBytes) {
            length = sampleBytes;
            truncated = true;
        }
        if (length == 0) {
            return single(StandardCharsets.US_ASCII, 1.0, false, 0, Reason.EMPTY, truncated);
        }

        Result bom = detectBom(data, length, truncated);
        if (bom != null) {
            return bom;
        }

        int nulEven = 0;
        int nulOdd = 0;
        int controls = 0;
        boolean ascii = true;
        for (int i = 0; i < length; i++) {
            int b = data[i] & 0xFF;
            if (b == 0) {
                if ((i & 1) == 0) {
                    nulEven++;
                } else {
                    nulOdd++;
                }
            } else if (isSuspiciousControl(b)) {
                controls++;
            }
            if (b >= 0x80) {
                ascii = false;
            }
        }

        if (nulEven + nulOdd > 0) {
            Result utf16 = detectUtf16ByNul(data, length, truncated, nulEven, nulOdd);
            return utf16 != null ? utf16 : binary(truncated);
        }
        // 控制字符超过一成：不像任何文本（合法的多字节编码的尾字节都 >= 0x40，不会贡献控制字符）
        if (controls * 10L > length) {
            return binary(truncated);
        }
        if (ascii) {
            return single(StandardCharsets.US_ASCII, 1.0, false, 0, Reason.ASCII, truncated);
        }

        List<Candidate> raw = new ArrayList<>();
        Utf8Scan utf8 = scanUtf8(data, length);
        boolean utf8Valid = utf8.error < 0 && (utf8.tailStart < 0 || truncated);
        double utf8Confidence = 0;
        if (utf8Valid) {
            utf8Confidence = Math.min(0.99, 1.0 - Math.pow(0.5, utf8.weight + 1));
        } else if (utf8.error < 0) {
            // 只有末尾缺了半个字：内容本身是 UTF-8，但严格转换会在末尾失败
            utf8Confidence = 0.5;
        }

        // 先验：本工具主要面向简体中文环境，得分相同（例如「你好」在 GBK 与 Big5 中都落在常用字区）时优先 GBK
        addDoubleByte(raw, scanGb(data, length, truncated), data, 1.0);
        addDoubleByte(raw, scanBig5(data, length, truncated), data, 0.97);
        addDoubleByte(raw, scanShiftJis(data, length, truncated), data, 0.95);
        if (!utf8Valid) {
            addUtf16ByText(raw, data, length, truncated);
        }
        raw.add(latinCandidate(data, length));
        raw.sort(Comparator.comparingDouble(Candidate::confidence).reversed());

        List<Candidate> ranked = new ArrayList<>();
        if (utf8Confidence > 0) {
            Reason reason = utf8Valid ? Reason.UTF8_VALID : Reason.UTF8_TRUNCATED_TAIL;
            ranked.add(new Candidate(StandardCharsets.UTF_8, utf8Confidence, reason));
        }
        if (utf8Valid) {
            // UTF-8 已经有效：其余解释只作为备选，置信度压到它之下
            for (Candidate other : raw) {
                double value = Math.min(other.confidence() * 0.5, utf8Confidence - 0.01);
                ranked.add(new Candidate(other.charset(), Math.max(0.01, value), other.reason()));
            }
        } else {
            double top = raw.get(0).confidence();
            double second = raw.size() > 1 ? raw.get(1).confidence() : 0;
            for (int i = 0; i < raw.size(); i++) {
                Candidate candidate = raw.get(i);
                double factor;
                if (i == 0) {
                    double ratio = top <= 0 ? 1 : second / top;
                    factor = 0.6 + 0.4 * (1 - ratio * ratio);
                } else {
                    factor = 0.6;
                }
                ranked.add(new Candidate(candidate.charset(), clamp(candidate.confidence() * factor),
                        candidate.reason()));
            }
            ranked.sort(Comparator.comparingDouble(Candidate::confidence).reversed());
        }
        Candidate best = ranked.get(0);
        return new Result(best.charset(), best.confidence(), false, 0, best.reason(), ranked, truncated);
    }

    // ==========================================
    // BOM 与 UTF-16
    // ==========================================
    private Result detectBom(byte[] data, int length, boolean truncated) {
        Charset charset = null;
        int bomLength = 0;
        if (startsWith(data, length, 0xFF, 0xFE, 0x00, 0x00) && UTF_32LE != null) {
            // UTF-32LE 的 BOM 以 UTF-16LE 的 BOM 开头，必须先判断
            charset = UTF_32LE;
            bomLength = 4;
        } else if (startsWith(data, length, 0x00, 0x00, 0xFE, 0xFF) && UTF_32BE != null) {
            charset = UTF_32BE;
            bomLength = 4;
        } else if (startsWith(data, length, 0xEF, 0xBB, 0xBF)) {
            charset = StandardCharsets.UTF_8;
            bomLength = 3;
        } else if (startsWith(data, length, 0xFF, 0xFE)) {
            charset = StandardCharsets.UTF_16LE;
            bomLength = 2;
        } else if (startsWith(data, length, 0xFE, 0xFF)) {
            charset = StandardCharsets.UTF_16BE;
            bomLength = 2;
        }
        if (charset == null) {
            return null;
        }
        int end = length;
        if (truncated) {
            end = completeBoundary(charset, data, bomLength, length);
        }
        boolean valid = strictlyDecodes(charset, data, bomLength, end);
        return single(charset, valid ? 1.0 : 0.75, true, bomLength,
                valid ? Reason.BOM : Reason.BOM_INVALID_BODY, truncated);
    }

    /** 被截断的前缀只校验到最后一个完整字符为止。 */
    private static int completeBoundary(Charset charset, byte[] data, int start, int length) {
        if (charset == StandardCharsets.UTF_8) {
            Utf8Scan scan = scanUtf8(data, length);
            return scan.tailStart >= 0 ? scan.tailStart : length;
        }
        int unit = (charset == UTF_32LE || charset == UTF_32BE) ? 4 : 2;
        int end = start + ((length - start) / unit) * unit;
        if (unit == 2 && end - start >= 2) {
            // 末尾若是高代理项，它的另一半在截断处之后
            int last = charset == StandardCharsets.UTF_16LE
                    ? ((data[end - 1] & 0xFF) << 8) | (data[end - 2] & 0xFF)
                    : ((data[end - 2] & 0xFF) << 8) | (data[end - 1] & 0xFF);
            if (Character.isHighSurrogate((char) last)) {
                end -= 2;
            }
        }
        return end;
    }

    private static Result detectUtf16ByNul(byte[] data, int length, boolean truncated, int nulEven, int nulOdd) {
        // ASCII 为主的 UTF-16LE：高字节（奇数位）为 0；BE 则相反。两边都有大量 NUL 的不是 UTF-16。
        Charset charset;
        int dominant;
        int minority;
        if (nulOdd > nulEven) {
            charset = StandardCharsets.UTF_16LE;
            dominant = nulOdd;
            minority = nulEven;
        } else {
            charset = StandardCharsets.UTF_16BE;
            dominant = nulEven;
            minority = nulOdd;
        }
        // 允许少量「反向」NUL：例如「一」(U+4E00) 在 BE 下就是 4E 00
        if (minority * 4L > dominant) {
            return null;
        }
        if ((length & 1) != 0 && !truncated) {
            return null;
        }
        int end = completeBoundary(charset, data, 0, length);
        String text = strictDecode(charset, data, 0, end);
        if (text == null) {
            return null;
        }
        double plausibility = TextPlausibility.score(text);
        if (plausibility < 0.6) {
            return null;
        }
        double confidence = clamp(0.5 + 0.47 * plausibility) * evidence(end / 2);
        return single(charset, Math.max(confidence, 0.5), false, 0, Reason.UTF16_NUL_PATTERN, truncated);
    }

    /**
     * 没有 NUL 的 UTF-16 只可能是几乎不含 ASCII 的中日文文本。按 LE/BE 各解一次，
     * 要求结果几乎全是常用字才收作候选——纯 ASCII 的字节两两拼起来也常落在汉字区（经典的「Bush hid the facts」问题）。
     */
    private static void addUtf16ByText(List<Candidate> out, byte[] data, int length, boolean truncated) {
        if ((length & 1) != 0 && !truncated) {
            return;
        }
        for (Charset charset : new Charset[]{StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE}) {
            int end = completeBoundary(charset, data, 0, length);
            String text = strictDecode(charset, data, 0, end);
            if (text == null) {
                continue;
            }
            double plausibility = TextPlausibility.score(text);
            if (plausibility >= 0.85) {
                out.add(new Candidate(charset, clamp(0.75 * plausibility * evidence(end / 2)),
                        Reason.UTF16_TEXT_PATTERN));
            }
        }
    }

    // ==========================================
    // UTF-8
    // ==========================================
    /** error: 第一个非法序列的偏移（-1 表示无）；tailStart: 末尾不完整序列的起点（-1 表示无）。 */
    private record Utf8Scan(int error, int tailStart, int weight) {
    }

    /**
     * 严格 UTF-8 结构校验：拒绝过长编码（C0/C1、E0 80..9F、F0 80..8F）、代理项（ED A0..BF）
     * 与超出 U+10FFFF 的码点（F4 90.. 及 F5..FF）。
     */
    private static Utf8Scan scanUtf8(byte[] data, int length) {
        int weight = 0;
        int i = 0;
        while (i < length) {
            int b = data[i] & 0xFF;
            if (b < 0x80) {
                i++;
                continue;
            }
            int need;
            int min = 0x80;
            int max = 0xBF;
            if (b >= 0xC2 && b <= 0xDF) {
                need = 1;
            } else if (b == 0xE0) {
                need = 2;
                min = 0xA0;
            } else if ((b >= 0xE1 && b <= 0xEC) || b == 0xEE || b == 0xEF) {
                need = 2;
            } else if (b == 0xED) {
                need = 2;
                max = 0x9F;
            } else if (b == 0xF0) {
                need = 3;
                min = 0x90;
            } else if (b >= 0xF1 && b <= 0xF3) {
                need = 3;
            } else if (b == 0xF4) {
                need = 3;
                max = 0x8F;
            } else {
                return new Utf8Scan(i, -1, weight);
            }
            for (int k = 1; k <= need; k++) {
                if (i + k >= length) {
                    return new Utf8Scan(-1, i, weight);
                }
                int c = data[i + k] & 0xFF;
                int lo = k == 1 ? min : 0x80;
                int hi = k == 1 ? max : 0xBF;
                if (c < lo || c > hi) {
                    return new Utf8Scan(i, -1, weight);
                }
            }
            weight += need == 1 ? 1 : 2;
            i += need + 1;
        }
        return new Utf8Scan(-1, -1, weight);
    }

    // ==========================================
    // 双字节编码
    // ==========================================
    /**
     * 双字节编码扫描结果。
     *
     * @param charset  结构校验后建议使用的编码
     * @param valid    结构是否有效
     * @param end      校验到的边界（截断样本时停在最后一个完整字符之后）
     * @param chars    非 ASCII 字符数
     * @param scoreSum 这些字符的常用度得分之和
     */
    private record DoubleByteScan(Charset charset, boolean valid, int end, int chars, double scoreSum) {
        static DoubleByteScan invalid() {
            return new DoubleByteScan(null, false, 0, 0, 0);
        }
    }

    private static void addDoubleByte(List<Candidate> out, DoubleByteScan scan, byte[] data, double prior) {
        if (!scan.valid() || scan.charset() == null || scan.chars() == 0) {
            return;
        }
        Charset charset = scan.charset();
        if (!strictlyDecodes(charset, data, 0, scan.end())) {
            // GBK 解不开时 GB18030 往往可以（例如 GBK 未收录、GB18030 以双字节收录的少数字）
            if (charset == GBK && GB18030 != null && strictlyDecodes(GB18030, data, 0, scan.end())) {
                charset = GB18030;
            } else {
                return;
            }
        }
        double plausibility = scan.scoreSum() / scan.chars();
        out.add(new Candidate(charset, clamp(Math.min(0.97, prior * plausibility * evidence(scan.chars()))),
                Reason.DOUBLE_BYTE_SCORE));
    }

    /** GBK（双字节）与 GB18030（四字节）：首字节 81–FE，尾字节 40–7E/80–FE，四字节形如 [81–FE][30–39][81–FE][30–39]。 */
    private static DoubleByteScan scanGb(byte[] data, int length, boolean truncated) {
        if (GBK == null && GB18030 == null) {
            return DoubleByteScan.invalid();
        }
        int chars = 0;
        int fourByte = 0;
        double sum = 0;
        int i = 0;
        while (i < length) {
            int b = data[i] & 0xFF;
            if (b < 0x80) {
                i++;
                continue;
            }
            if (b == 0x80 || b == 0xFF) {
                return DoubleByteScan.invalid();
            }
            if (i + 1 >= length) {
                if (truncated) {
                    break;
                }
                return DoubleByteScan.invalid();
            }
            int b2 = data[i + 1] & 0xFF;
            if (b2 >= 0x30 && b2 <= 0x39) {
                if (i + 3 >= length) {
                    if (truncated) {
                        break;
                    }
                    return DoubleByteScan.invalid();
                }
                int b3 = data[i + 2] & 0xFF;
                int b4 = data[i + 3] & 0xFF;
                if (b3 < 0x81 || b3 > 0xFE || b4 < 0x30 || b4 > 0x39) {
                    return DoubleByteScan.invalid();
                }
                fourByte++;
                chars++;
                sum += 0.3;
                i += 4;
                continue;
            }
            if (b2 < 0x40 || b2 == 0x7F || b2 == 0xFF) {
                return DoubleByteScan.invalid();
            }
            chars++;
            sum += gbScore(b, b2);
            i += 2;
        }
        Charset charset = fourByte > 0 || GBK == null ? GB18030 : GBK;
        return new DoubleByteScan(charset, charset != null, i, chars, sum);
    }

    /** 按 GB2312/GBK 分区给双字节字符打常用度分。 */
    private static double gbScore(int lead, int trail) {
        if (trail >= 0xA1) {
            if (lead >= 0xB0 && lead <= 0xD7) {
                return 1.0;
            }
            if (lead == 0xA1 || lead == 0xA3) {
                return 1.0;
            }
            if (lead >= 0xD8 && lead <= 0xF7) {
                return 0.5;
            }
            if (lead == 0xA2) {
                return 0.7;
            }
            if (lead == 0xA4 || lead == 0xA5) {
                return 0.3;
            }
            if (lead >= 0xA6 && lead <= 0xA9) {
                return 0.4;
            }
            // AAA1–AFFE、F8A1–FEFE：用户自定义区
            return 0.0;
        }
        if (lead >= 0xA1 && lead <= 0xA7) {
            // A140–A7A0：用户自定义区
            return 0.0;
        }
        if (lead == 0xA8 || lead == 0xA9) {
            return 0.3;
        }
        // GBK/3、GBK/4 扩展汉字：合法但多为生僻字
        return 0.2;
    }

    /** Big5：首字节 81–FE，尾字节 40–7E/A1–FE。 */
    private static DoubleByteScan scanBig5(byte[] data, int length, boolean truncated) {
        if (BIG5 == null) {
            return DoubleByteScan.invalid();
        }
        int chars = 0;
        double sum = 0;
        int i = 0;
        while (i < length) {
            int b = data[i] & 0xFF;
            if (b < 0x80) {
                i++;
                continue;
            }
            if (b == 0x80 || b == 0xFF) {
                return DoubleByteScan.invalid();
            }
            if (i + 1 >= length) {
                if (truncated) {
                    break;
                }
                return DoubleByteScan.invalid();
            }
            int b2 = data[i + 1] & 0xFF;
            if (!((b2 >= 0x40 && b2 <= 0x7E) || (b2 >= 0xA1 && b2 <= 0xFE))) {
                return DoubleByteScan.invalid();
            }
            int code = (b << 8) | b2;
            chars++;
            if (code >= 0xA440 && code <= 0xC67E) {
                sum += 1.0;
            } else if (code >= 0xA140 && code <= 0xA3BF) {
                sum += 0.8;
            } else if (code >= 0xC940 && code <= 0xF9D5) {
                sum += 0.4;
            } else if (code >= 0xC6A1 && code <= 0xC8FE) {
                sum += 0.1;
            }
            i += 2;
        }
        return new DoubleByteScan(BIG5, true, i, chars, sum);
    }

    /** Shift_JIS（按 windows-31j 解码）：单字节半角片假名 A1–DF，双字节首字节 81–9F/E0–FC。 */
    private static DoubleByteScan scanShiftJis(byte[] data, int length, boolean truncated) {
        if (WINDOWS_31J == null) {
            return DoubleByteScan.invalid();
        }
        int chars = 0;
        double sum = 0;
        int i = 0;
        while (i < length) {
            int b = data[i] & 0xFF;
            if (b < 0x80) {
                i++;
                continue;
            }
            if (b >= 0xA1 && b <= 0xDF) {
                chars++;
                sum += 0.3;
                i++;
                continue;
            }
            if (!((b >= 0x81 && b <= 0x9F) || (b >= 0xE0 && b <= 0xFC))) {
                return DoubleByteScan.invalid();
            }
            if (i + 1 >= length) {
                if (truncated) {
                    break;
                }
                return DoubleByteScan.invalid();
            }
            int b2 = data[i + 1] & 0xFF;
            if (b2 < 0x40 || b2 == 0x7F || b2 > 0xFC) {
                return DoubleByteScan.invalid();
            }
            chars++;
            sum += shiftJisScore((b << 8) | b2);
            i += 2;
        }
        return new DoubleByteScan(WINDOWS_31J, true, i, chars, sum);
    }

    private static double shiftJisScore(int code) {
        if (code >= 0x829F && code <= 0x82F1) {
            return 1.0; // 平假名
        }
        if (code >= 0x8340 && code <= 0x8396) {
            return 1.0; // 片假名
        }
        if (code >= 0x8140 && code <= 0x81FC) {
            return 0.9; // 标点
        }
        if (code >= 0x889F && code <= 0x9872) {
            return 0.9; // JIS 第一水准汉字
        }
        if (code >= 0x824F && code <= 0x829A) {
            return 0.7; // 全角数字与字母
        }
        if (code >= 0x8740 && code <= 0x879C) {
            return 0.5; // NEC 特殊字符（①等）
        }
        if ((code >= 0x839F && code <= 0x83D6) || (code >= 0x8440 && code <= 0x84BE)) {
            return 0.4;
        }
        if (code >= 0x989F && code <= 0xEAA4) {
            return 0.4; // 第二水准汉字
        }
        if ((code >= 0xED40 && code <= 0xEEFC) || (code >= 0xFA40 && code <= 0xFC4B)) {
            return 0.2;
        }
        return 0.0;
    }

    // ==========================================
    // 单字节兜底
    // ==========================================
    /**
     * 西文单字节编码。真正的西文文本里，重音字母是夹在 ASCII 字母之间的；
     * 连续一串高位字节被解释成「ÖÐÎÄ」这种样子，恰恰说明它不是西文。
     */
    private static Candidate latinCandidate(byte[] data, int length) {
        int high = 0;
        boolean usesWindowsRange = false;
        boolean hasUndefined = false;
        double sum = 0;
        for (int i = 0; i < length; i++) {
            int b = data[i] & 0xFF;
            if (b < 0x80) {
                continue;
            }
            high++;
            if (b <= 0x9F) {
                if (b == 0x81 || b == 0x8D || b == 0x8F || b == 0x90 || b == 0x9D) {
                    hasUndefined = true;
                } else {
                    usesWindowsRange = true;
                    boolean punctuation = (b >= 0x91 && b <= 0x94) || b == 0x96 || b == 0x97
                            || b == 0x85 || b == 0x80 || b == 0x99;
                    sum += punctuation ? 0.6 : 0.4;
                }
            } else if (b == 0xA0) {
                sum += 0.5;
            } else if (b <= 0xBF || b == 0xD7 || b == 0xF7) {
                sum += 0.2;
            } else {
                boolean nearLetter = (i > 0 && isAsciiLetter(data[i - 1]))
                        || (i + 1 < length && isAsciiLetter(data[i + 1]));
                sum += nearLetter ? 1.0 : 0.25;
            }
        }
        Charset charset = usesWindowsRange && !hasUndefined && WINDOWS_1252 != null
                ? WINDOWS_1252 : StandardCharsets.ISO_8859_1;
        double plausibility = high == 0 ? 1.0 : sum / high;
        double confidence = Math.max(0.05, plausibility * evidence(high) * 0.85);
        return new Candidate(charset, clamp(confidence), Reason.SINGLE_BYTE_FALLBACK);
    }

    // ==========================================
    // 工具
    // ==========================================
    /** 证据量折扣：2 个字约 0.57，10 个字约 0.89，30 个字以上接近 1。 */
    private static double evidence(int chars) {
        return 1.0 - 0.6 * Math.exp(-chars / 6.0);
    }

    private static boolean isSuspiciousControl(int b) {
        if (b == 0x7F) {
            return true;
        }
        if (b >= 0x20) {
            return false;
        }
        // 制表、换行、回车、换页、ESC（ANSI 着色）、SUB（DOS 文件尾）都属于正常文本
        return b != 0x09 && b != 0x0A && b != 0x0D && b != 0x0C && b != 0x1B && b != 0x1A;
    }

    private static boolean isAsciiLetter(byte value) {
        int b = value & 0xFF;
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z');
    }

    private static boolean startsWith(byte[] data, int length, int... prefix) {
        if (length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    static boolean strictlyDecodes(Charset charset, byte[] data, int from, int to) {
        return strictDecode(charset, data, from, to) != null;
    }

    private static String strictDecode(Charset charset, byte[] data, int from, int to) {
        try {
            CharBuffer chars = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, from, Math.max(0, to - from)));
            return chars.toString();
        } catch (CharacterCodingException malformed) {
            return null;
        }
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static Result single(Charset charset, double confidence, boolean bom, int bomLength,
                                 Reason reason, boolean truncated) {
        return new Result(charset, confidence, bom, bomLength, reason,
                List.of(new Candidate(charset, confidence, reason)), truncated);
    }

    private static Result binary(boolean truncated) {
        return new Result(null, 1.0, false, 0, Reason.BINARY, List.of(), truncated);
    }
}
