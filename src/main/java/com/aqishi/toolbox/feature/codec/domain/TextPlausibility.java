package com.aqishi.toolbox.feature.codec.domain;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;

/**
 * 给一段已解码的文本打「像不像正常文字」的分（0..1）。
 *
 * <p>编码探测和乱码修复都要回答同一个问题：这一串字符是人写的，还是字节被错误解读后的产物？
 * 这里不带任何词频表，而是借用 GB2312 本身的分级：GB2312 一级汉字（区位 B0–D7）正是按使用频率挑出的
 * 3755 个常用字，二级汉字次之，GBK 扩展区与 CJK 扩展 A/B 区基本属于生僻字。
 * 乱码恰好相反——错误解读出来的汉字大量落在生僻区、韩文音节区、私用区或拉丁符号上。</p>
 */
public final class TextPlausibility {

    private static final int CJK_FIRST = 0x4E00;
    private static final int CJK_LAST = 0x9FFF;
    /** 为控制耗时，只对前这么多个码点打分。 */
    private static final int SCORE_LIMIT = 65_536;

    private TextPlausibility() {
    }

    /** 整段文本的平均分；空串记 1。 */
    public static double score(CharSequence text) {
        if (text == null || text.length() == 0) {
            return 1.0;
        }
        double sum = 0;
        int count = 0;
        for (int i = 0; i < text.length() && count < SCORE_LIMIT; ) {
            int cp = Character.codePointAt(text, i);
            sum += codePointScore(cp);
            count++;
            i += Character.charCount(cp);
        }
        return count == 0 ? 1.0 : sum / count;
    }

    /**
     * 只统计非 ASCII 字符的平均分；全是 ASCII 时返回 1。
     *
     * <p>乱码修复比较候选时用它：ASCII 部分在各候选间通常原样不变，算进平均值只会稀释差异。</p>
     */
    public static double nonAsciiScore(CharSequence text) {
        if (text == null) {
            return 1.0;
        }
        double sum = 0;
        int count = 0;
        for (int i = 0; i < text.length() && count < SCORE_LIMIT; ) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            if (cp < 0x80) {
                continue;
            }
            sum += codePointScore(cp);
            count++;
        }
        return count == 0 ? 1.0 : sum / count;
    }

    /** 单个码点的可信度。 */
    public static double codePointScore(int cp) {
        if (cp == '\t' || cp == '\n' || cp == '\r' || cp == '\f') {
            return 1.0;
        }
        if (cp < 0x20 || cp == 0x7F) {
            return 0.0;
        }
        if (cp < 0x7F) {
            return 1.0;
        }
        if (cp <= 0x9F) {
            // C1 控制字符：正常文本里几乎不会出现，却是 UTF-8 被当 Latin-1 读时的典型产物
            return 0.0;
        }
        if (cp == 0xA0) {
            return 0.5;
        }
        if (cp <= 0xBF || cp == 0xD7 || cp == 0xF7) {
            return 0.25;
        }
        if (cp <= 0xFF) {
            return 0.5;
        }
        if (cp <= 0x024F) {
            return 0.4;
        }
        if (cp >= 0x0370 && cp <= 0x04FF) {
            return 0.4;
        }
        if (cp >= 0x2000 && cp <= 0x206F) {
            return 0.8;
        }
        if (cp >= 0x2500 && cp <= 0x257F) {
            return 0.3;
        }
        if (cp >= 0x3000 && cp <= 0x303F) {
            return 1.0;
        }
        if (cp >= 0x3040 && cp <= 0x30FF) {
            return 0.8;
        }
        if (cp >= 0x3400 && cp <= 0x4DBF) {
            return 0.15;
        }
        if (cp >= CJK_FIRST && cp <= CJK_LAST) {
            switch (CjkLevels.level(cp)) {
                case 1:
                    return 1.0;
                case 2:
                    return 0.6;
                default:
                    return 0.3;
            }
        }
        if (cp >= 0xAC00 && cp <= 0xD7A3) {
            return 0.3;
        }
        if (cp >= 0xE000 && cp <= 0xF8FF) {
            return 0.0;
        }
        if (cp >= 0xFF61 && cp <= 0xFF9F) {
            return 0.3;
        }
        if (cp >= 0xFF00 && cp <= 0xFFEF) {
            return 0.9;
        }
        if (cp == 0xFFFD || cp == 0xFEFF) {
            return 0.0;
        }
        if (cp >= 0x20000 && cp <= 0x3134F) {
            return 0.15;
        }
        if (cp >= 0xF0000) {
            return 0.0;
        }
        return 0.2;
    }

    /**
     * 基本区汉字在 GB2312 中的级别：1 = 一级常用字，2 = 二级次常用字，3 = 其余。
     *
     * <p>用一次性计算好的查表代替逐字编码：表只有两万多项，类首次使用时算一遍即可。</p>
     */
    private static final class CjkLevels {
        private static final byte[] LEVELS = compute();

        static int level(int cp) {
            return LEVELS[cp - CJK_FIRST];
        }

        private static byte[] compute() {
            byte[] levels = new byte[CJK_LAST - CJK_FIRST + 1];
            java.util.Arrays.fill(levels, (byte) 3);
            Charset gbk = EncodingDetector.lookup("GBK");
            if (gbk == null) {
                return levels;
            }
            CharsetEncoder encoder = gbk.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer out = ByteBuffer.allocate(8);
            char[] single = new char[1];
            for (int cp = CJK_FIRST; cp <= CJK_LAST; cp++) {
                single[0] = (char) cp;
                out.clear();
                encoder.reset();
                if (encoder.encode(CharBuffer.wrap(single), out, true).isError() || out.position() != 2) {
                    continue;
                }
                int lead = out.get(0) & 0xFF;
                int trail = out.get(1) & 0xFF;
                if (trail >= 0xA1) {
                    if (lead >= 0xB0 && lead <= 0xD7) {
                        levels[cp - CJK_FIRST] = 1;
                    } else if (lead >= 0xD8 && lead <= 0xF7) {
                        levels[cp - CJK_FIRST] = 2;
                    }
                }
            }
            return levels;
        }
    }
}
