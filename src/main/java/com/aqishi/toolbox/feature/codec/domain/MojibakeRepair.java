package com.aqishi.toolbox.feature.codec.domain;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 乱码修复：尝试逆转常见的「按错误编码解码」过程（纯函数）。
 *
 * <p>乱码的成因几乎总是：字节 B 按编码 X 写出，却被按编码 Y 读成了文本 T。
 * 只要 Y 的解码没有丢信息，把 T 按 Y 重新编码就能拿回 B，再按 X 解码即得原文。
 * 这里枚举常见的 (Y, X) 组合，并支持两层嵌套（乱码又被当成正常文本再错一次），
 * 用「严格往返 + 文本可信度」给候选排序。</p>
 *
 * <p>不可逆的情形单独报告：GBK 字节被当 UTF-8 读时，非法序列已被替换成 U+FFFD，
 * 再存成 GBK 就成了著名的「锟斤拷」——原始字节已经丢失，任何变换都救不回来。</p>
 */
public final class MojibakeRepair {

    /** 超过这个长度只做一层逆转，避免 36 种两层组合在大文本上跑太久。 */
    private static final int NESTED_LIMIT = 200_000;
    /** 候选必须比原文可信度高出这么多才值得展示。 */
    private static final double MIN_GAIN = 0.05;

    /** 变换链中的一步：按 {@code encodeAs} 重新编码，再按 {@code decodeAs} 解码。 */
    public record Step(String encodeAs, String decodeAs) {
    }

    /** 一个修复候选。 */
    public record Candidate(String text, List<Step> chain, double score) {
        public Candidate {
            chain = List.copyOf(chain);
        }
    }

    /**
     * 修复报告。
     *
     * @param inputScore        输入文本本身的可信度
     * @param replacementChars  输入中 U+FFFD 的个数
     * @param kaoMarkers        输入中「锟斤拷」三字组出现的次数
     */
    public record Report(List<Candidate> candidates, double inputScore, int replacementChars, int kaoMarkers) {
        public Report {
            candidates = List.copyOf(candidates);
        }

        /** 输入含有已被替换掉的字节：这部分内容无法复原。 */
        public boolean irreversible() {
            return replacementChars > 0 || kaoMarkers > 0;
        }
    }

    /** 宽松的 windows-1252：它未定义的 5 个字节在很多工具里被当作同值的 C1 控制字符保留。 */
    static final String LATIN = "windows-1252";

    private record Transform(String encodeAs, String decodeAs) {
    }

    private static final List<Transform> TRANSFORMS = List.of(
            // UTF-8 字节被当成西文读：「ä¸­æ–‡」
            new Transform(LATIN, "UTF-8"),
            // UTF-8 字节被当成 GBK 读：「涓枃」这一类
            new Transform("GBK", "UTF-8"),
            new Transform("Big5", "UTF-8"),
            new Transform("windows-31j", "UTF-8"),
            // GBK 字节被当成西文读：「ÖÐÎÄ」
            new Transform(LATIN, "GBK"),
            new Transform(LATIN, "Big5"));

    /** 「锟斤拷」三个字的码点（避免源码字面量里出现汉字）。 */
    private static final int[] KAO = {0x951F, 0x65A4, 0x62F7};

    private MojibakeRepair() {
    }

    public static Report repair(String input) {
        Objects.requireNonNull(input, "input");
        double inputScore = TextPlausibility.nonAsciiScore(input);
        int replacement = 0;
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) == 0xFFFD) {
                replacement++;
            }
        }
        int kao = countKao(input);

        Map<String, Candidate> found = new LinkedHashMap<>();
        List<Candidate> firstLevel = new ArrayList<>();
        for (Transform transform : TRANSFORMS) {
            String output = apply(input, transform);
            if (output != null && !output.equals(input)) {
                Candidate candidate = new Candidate(output,
                        List.of(new Step(transform.encodeAs(), transform.decodeAs())),
                        TextPlausibility.nonAsciiScore(output));
                firstLevel.add(candidate);
                found.putIfAbsent(output, candidate);
            }
        }
        if (input.length() <= NESTED_LIMIT) {
            for (Candidate first : firstLevel) {
                for (Transform transform : TRANSFORMS) {
                    String output = apply(first.text(), transform);
                    if (output == null || output.equals(first.text()) || output.equals(input)) {
                        continue;
                    }
                    List<Step> chain = new ArrayList<>(first.chain());
                    chain.add(new Step(transform.encodeAs(), transform.decodeAs()));
                    Candidate candidate = new Candidate(output, chain, TextPlausibility.nonAsciiScore(output));
                    // 同一结果保留更短的链：一步能到就不必说成两步
                    found.putIfAbsent(output, candidate);
                }
            }
        }

        List<Candidate> ranked = new ArrayList<>();
        for (Candidate candidate : found.values()) {
            if (candidate.score() >= inputScore + MIN_GAIN) {
                ranked.add(candidate);
            }
        }
        ranked.sort(Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparingInt(candidate -> candidate.chain().size())
                .thenComparingInt(candidate -> candidate.text().length()));
        return new Report(ranked, inputScore, replacement, kao);
    }

    /** 严格往返：编码与解码任一步有字符丢失即视为此路不通。 */
    static String apply(String text, Transform transform) {
        byte[] bytes = encode(text, transform.encodeAs());
        if (bytes == null) {
            return null;
        }
        Charset decodeAs = EncodingDetector.lookup(transform.decodeAs());
        if (decodeAs == null) {
            return null;
        }
        try {
            return decodeAs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalid) {
            return null;
        }
    }

    private static byte[] encode(String text, String charsetName) {
        if (LATIN.equals(charsetName)) {
            return encodeLatin(text);
        }
        Charset charset = EncodingDetector.lookup(charsetName);
        if (charset == null) {
            return null;
        }
        try {
            ByteBuffer buffer = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException unmappable) {
            return null;
        }
    }

    /**
     * windows-1252 与 ISO-8859-1 的并集：1252 专有的弯引号、破折号、€ 等按 1252 还原，
     * U+0000–U+00FF 其余字符（含 1252 未定义位置上的 C1 控制符）按 Latin-1 原值还原。
     */
    private static byte[] encodeLatin(String text) {
        Charset cp1252 = EncodingDetector.lookup("windows-1252");
        CharsetEncoder encoder = cp1252 == null ? null : cp1252.newEncoder();
        byte[] out = new byte[text.length()];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 0x7F || (c >= 0xA0 && c <= 0xFF) || (c >= 0x80 && c <= 0x9F)) {
                out[i] = (byte) c;
                continue;
            }
            if (encoder == null || !encoder.canEncode(c)) {
                return null;
            }
            byte[] mapped = String.valueOf(c).getBytes(cp1252);
            if (mapped.length != 1) {
                return null;
            }
            out[i] = mapped[0];
        }
        return out;
    }

    private static int countKao(String text) {
        int count = 0;
        for (int i = 0; i + 2 < text.length(); i++) {
            if (text.charAt(i) == KAO[0] && text.charAt(i + 1) == KAO[1] && text.charAt(i + 2) == KAO[2]) {
                count++;
                i += 2;
            }
        }
        return count;
    }
}
