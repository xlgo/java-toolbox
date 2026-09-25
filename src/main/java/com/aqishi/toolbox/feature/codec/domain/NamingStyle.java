package com.aqishi.toolbox.feature.codec.domain;

import java.util.List;
import java.util.Locale;

/**
 * 标识符命名风格。每种风格只负责「把已经切好的单词拼起来」，切词见 {@link NamingConverter#split(String)}。
 *
 * <p>大小写转换一律使用 {@link Locale#ROOT}：在土耳其语环境下用默认 Locale 会把 {@code id} 大写成
 * {@code İD}，生成的标识符在代码里根本对不上。</p>
 */
public enum NamingStyle {
    CAMEL("camelCase"),
    PASCAL("PascalCase"),
    SNAKE("snake_case"),
    SCREAMING_SNAKE("SCREAMING_SNAKE_CASE"),
    KEBAB("kebab-case"),
    SCREAMING_KEBAB("SCREAMING-KEBAB-CASE"),
    TRAIN("Train-Case"),
    DOT("dot.case"),
    PATH("path/case"),
    TITLE("Title Case"),
    SENTENCE("Sentence case"),
    SPACE("space case"),
    FLAT("flatcase"),
    UPPER_FLAT("UPPERFLATCASE");

    private final String sample;

    NamingStyle(String sample) {
        this.sample = sample;
    }

    /** 该风格自身的写法示例，同时用作不需要翻译的展示名。 */
    public String getSample() {
        return sample;
    }

    /**
     * 按本风格拼接单词。
     *
     * @param keepAcronyms 为 true 时，首字母大写类风格（Pascal、camel 的非首词、Train、Title）
     *                     把已知缩写保持全大写，如 {@code HTTPServer} 而不是 {@code HttpServer}
     */
    public String join(List<String> words, boolean keepAcronyms) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            switch (this) {
                case CAMEL:
                    sb.append(i == 0 ? lower(word) : capitalized(word, keepAcronyms));
                    break;
                case PASCAL:
                    sb.append(capitalized(word, keepAcronyms));
                    break;
                case SNAKE:
                    appendJoined(sb, i, "_", lower(word));
                    break;
                case SCREAMING_SNAKE:
                    appendJoined(sb, i, "_", upper(word));
                    break;
                case KEBAB:
                    appendJoined(sb, i, "-", lower(word));
                    break;
                case SCREAMING_KEBAB:
                    appendJoined(sb, i, "-", upper(word));
                    break;
                case TRAIN:
                    appendJoined(sb, i, "-", capitalized(word, keepAcronyms));
                    break;
                case DOT:
                    appendJoined(sb, i, ".", lower(word));
                    break;
                case PATH:
                    appendJoined(sb, i, "/", lower(word));
                    break;
                case TITLE:
                    appendJoined(sb, i, " ", capitalized(word, keepAcronyms));
                    break;
                case SENTENCE:
                    appendJoined(sb, i, " ", i == 0 ? capitalized(word, keepAcronyms)
                            : keepAcronyms ? acronymOrLower(word) : lower(word));
                    break;
                case SPACE:
                    appendJoined(sb, i, " ", lower(word));
                    break;
                case FLAT:
                    sb.append(lower(word));
                    break;
                case UPPER_FLAT:
                    sb.append(upper(word));
                    break;
                default:
                    throw new IllegalStateException("Unhandled style: " + this);
            }
        }
        return sb.toString();
    }

    private static void appendJoined(StringBuilder sb, int index, String separator, String word) {
        if (index > 0) {
            sb.append(separator);
        }
        sb.append(word);
    }

    private static String lower(String word) {
        return word.toLowerCase(Locale.ROOT);
    }

    private static String upper(String word) {
        return word.toUpperCase(Locale.ROOT);
    }

    private static String acronymOrLower(String word) {
        String acronym = NamingConverter.canonicalAcronym(word);
        return acronym != null ? acronym : lower(word);
    }

    private static String capitalized(String word, boolean keepAcronyms) {
        if (keepAcronyms) {
            String acronym = NamingConverter.canonicalAcronym(word);
            if (acronym != null) {
                return acronym;
            }
        }
        String lower = lower(word);
        if (lower.isEmpty()) {
            return lower;
        }
        int first = lower.codePointAt(0);
        return new StringBuilder(lower.length())
                .appendCodePoint(Character.toTitleCase(first))
                .append(lower, Character.charCount(first), lower.length())
                .toString();
    }
}
