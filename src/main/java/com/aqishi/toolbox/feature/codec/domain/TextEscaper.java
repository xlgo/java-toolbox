package com.aqishi.toolbox.feature.codec.domain;

import com.aqishi.toolbox.feature.codec.domain.UnescapeException.Code;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 十种常见字面量格式的转义与反转义。
 *
 * <p>设计约束：</p>
 * <ul>
 *   <li>每种格式的 {@code unescape(escape(s)) == s} 都成立（XML 例外：XML 1.0 连字符引用都不允许的
 *       控制字符无法表示，转义时替换为 U+FFFD 并在结果里给出警告）。</li>
 *   <li>严格模式下遇到非法序列抛出 {@link UnescapeException}，带错误码和原始下标，界面据此定位；
 *       宽松模式把非法序列原样保留并记为 {@link Issue}，适合处理「大体正确」的日志片段。</li>
 *   <li>不产出任何面向用户的文案，本地化交给界面层。</li>
 * </ul>
 */
public final class TextEscaper {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** 正则元字符：在 java.util.regex 的字符类之外有特殊含义的全部字符。 */
    private static final String REGEX_META = "\\^$.|?*+()[]{}";

    /**
     * HTML5 对 &amp;#128; ~ &amp;#159; 的重映射：历史上这段数值被当作 Windows-1252 解释，
     * 浏览器至今保持这一行为（0 表示不重映射）。
     */
    private static final char[] WINDOWS_1252_C1 = {
            0x20AC, 0, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
            0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0, 0x017D, 0,
            0, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
            0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0, 0x017E, 0x0178};

    private static final int HEX_TRUNCATED = -1;
    private static final int HEX_INVALID = -2;

    private TextEscaper() {
    }

    // ==========================================
    // 选项与结果
    // ==========================================

    /** 转义选项。不同格式只读取与自己相关的字段，其余忽略。 */
    public static final class Options {
        private boolean escapeNonAscii;
        private boolean lenient;
        private boolean scriptSafe;
        private char csvDelimiter = ',';
        private boolean sqlMysql;
        private boolean sqlQuoted;
        private boolean propertiesKey;

        public static Options defaults() {
            return new Options();
        }

        /** JAVA / JSON / JAVASCRIPT / HTML / XML / PROPERTIES：非 ASCII 字符也转成转义序列。 */
        public Options escapeNonAscii(boolean value) {
            this.escapeNonAscii = value;
            return this;
        }

        /** 反转义时把非法序列原样保留（记为 {@link Issue}）而不是抛异常。 */
        public Options lenient(boolean value) {
            this.lenient = value;
            return this;
        }

        /** JSON / JAVASCRIPT：转义 &lt;/ 与 U+2028/U+2029，使结果可以安全嵌入 script 标签。 */
        public Options scriptSafe(boolean value) {
            this.scriptSafe = value;
            return this;
        }

        /** CSV 字段分隔符，常见为逗号、分号、制表符。 */
        public Options csvDelimiter(char value) {
            this.csvDelimiter = value;
            return this;
        }

        /** SQL：使用 MySQL 的反斜杠转义规则（默认是 ANSI 的单引号加倍）。 */
        public Options sqlMysql(boolean value) {
            this.sqlMysql = value;
            return this;
        }

        /** SQL：转义结果带外层单引号；反转义时要求输入带外层单引号。 */
        public Options sqlQuoted(boolean value) {
            this.sqlQuoted = value;
            return this;
        }

        /** PROPERTIES：按「键」转义（所有空格都要转义），默认按「值」转义（只转义首个空格）。 */
        public Options propertiesKey(boolean value) {
            this.propertiesKey = value;
            return this;
        }

        public boolean isEscapeNonAscii() {
            return escapeNonAscii;
        }

        public boolean isLenient() {
            return lenient;
        }

        public boolean isScriptSafe() {
            return scriptSafe;
        }

        public char getCsvDelimiter() {
            return csvDelimiter;
        }

        public boolean isSqlMysql() {
            return sqlMysql;
        }

        public boolean isSqlQuoted() {
            return sqlQuoted;
        }

        public boolean isPropertiesKey() {
            return propertiesKey;
        }
    }

    /** 转换过程中发现、但没有中断转换的问题（宽松模式保留的非法序列、XML 替换掉的非法字符）。 */
    public record Issue(Code code, int offset, int length) {
    }

    /** 转换结果：文本与问题列表（下标均指向原始输入）。 */
    public record Result(String text, List<Issue> issues) {
        public Result {
            Objects.requireNonNull(text, "text");
            issues = List.copyOf(issues);
        }

        public boolean hasIssues() {
            return !issues.isEmpty();
        }
    }

    // ==========================================
    // 入口
    // ==========================================

    public static String escape(String input, EscapeFormat format) {
        return escape(input, format, Options.defaults()).text();
    }

    public static String unescape(String input, EscapeFormat format) throws UnescapeException {
        return unescape(input, format, Options.defaults()).text();
    }

    public static Result escape(String input, EscapeFormat format, Options options) {
        String s = input == null ? "" : input;
        Options o = options == null ? Options.defaults() : options;
        List<Issue> issues = new ArrayList<>();
        String text;
        switch (Objects.requireNonNull(format, "format")) {
            case JAVA:
                text = escapeJava(s, o);
                break;
            case JSON:
                text = escapeJson(s, o, false);
                break;
            case JAVASCRIPT:
                text = escapeJson(s, o, true);
                break;
            case UNICODE:
                text = escapeUnicode(s);
                break;
            case HTML:
                text = escapeHtml(s, o);
                break;
            case XML:
                text = escapeXml(s, o, issues);
                break;
            case CSV:
                text = escapeCsv(s, o);
                break;
            case SQL:
                text = escapeSql(s, o);
                break;
            case REGEX:
                text = escapeRegex(s);
                break;
            case PROPERTIES:
                text = escapeProperties(s, o);
                break;
            default:
                throw new IllegalArgumentException("Unsupported format: " + format);
        }
        return new Result(text, issues);
    }

    public static Result unescape(String input, EscapeFormat format, Options options)
            throws UnescapeException {
        String s = input == null ? "" : input;
        Decoder d = new Decoder(s, options == null ? Options.defaults() : options);
        switch (Objects.requireNonNull(format, "format")) {
            case JAVA: {
                int[] bounds = literalBounds(s, '"');
                unescapeJava(d, bounds[0], bounds[1]);
                break;
            }
            case JSON: {
                int[] bounds = literalBounds(s, '"');
                unescapeJson(d, bounds[0], bounds[1]);
                break;
            }
            case JAVASCRIPT: {
                int[] bounds = literalBounds(s, '"', '\'');
                unescapeJavaScript(d, bounds[0], bounds[1]);
                break;
            }
            case UNICODE:
                unescapeUnicode(d);
                break;
            case HTML:
                unescapeMarkup(d, false);
                break;
            case XML:
                unescapeMarkup(d, true);
                break;
            case CSV:
                unescapeCsv(d);
                break;
            case SQL:
                unescapeSql(d);
                break;
            case REGEX:
                unescapeRegex(d);
                break;
            case PROPERTIES:
                unescapeProperties(d);
                break;
            default:
                throw new IllegalArgumentException("Unsupported format: " + format);
        }
        return new Result(d.out.toString(), d.issues);
    }

    // ==========================================
    // 转义
    // ==========================================

    private static String escapeJava(String s, Options o) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\b':
                    sb.append("\\b");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                default:
                    // 单引号在字符串字面量里无需转义，保留原样让结果更易读；反转义仍接受 \'
                    if (isControl(c) || (o.escapeNonAscii && c > 0x7E)) {
                        appendU(sb, c);
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** JSON 与 JavaScript 共用：JS 额外转义单引号，并且总是转义 U+2028/U+2029。 */
    private static String escapeJson(String s, Options o, boolean javaScript) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\'':
                    sb.append(javaScript ? "\\'" : "'");
                    break;
                case '/':
                    // 只有 </ 会提前结束 script 标签；单独的斜杠保留，避免满屏 \/
                    sb.append(o.scriptSafe && i > 0 && s.charAt(i - 1) == '<' ? "\\/" : "/");
                    break;
                case '\u2028':
                case '\u2029':
                    // ES2019 之前的 JS 引擎把这两个字符当换行，出现在字符串字面量里直接语法错误
                    if (javaScript || o.scriptSafe) {
                        appendU(sb, c);
                    } else {
                        sb.append(c);
                    }
                    break;
                default:
                    if (c < 0x20 || (o.escapeNonAscii && c > 0x7E)) {
                        appendU(sb, c);
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static String escapeUnicode(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0x7F) {
                appendU(sb, c);
            } else if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == 'u') {
                // 原文里本来就有「\\u」字样时，把反斜杠自身写成 \\u005C，否则反转义会把它误当成转义序列
                appendU(sb, c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escapeHtml(String s, Options o) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            switch (cp) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    // &apos; 在 HTML4 中不存在，老浏览器不认，用数值引用最稳妥
                    sb.append("&#39;");
                    break;
                default:
                    // C1 控制字符的数值引用会被浏览器按 Windows-1252 重映射，孤立代理项的引用会变成 U+FFFD，
                    // 这两类保持原样才能往返
                    if (o.escapeNonAscii && cp > 0x9F && !Character.isSurrogate((char) cp)) {
                        sb.append("&#").append(cp).append(';');
                    } else {
                        sb.appendCodePoint(cp);
                    }
            }
        }
        return sb.toString();
    }

    private static String escapeXml(String s, Options o, List<Issue> issues) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int start = i;
            i += Character.charCount(cp);
            switch (cp) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                case '\r':
                    // 解析器会把原样的 CR 规范化成 LF，写成字符引用才能保住
                    sb.append("&#xD;");
                    break;
                default:
                    if (!isXmlChar(cp)) {
                        // XML 1.0 连 &#x1; 这样的字符引用都禁止，只能替换并告知调用方
                        issues.add(new Issue(Code.XML_ILLEGAL_CHAR, start, i - start));
                        sb.append(o.escapeNonAscii ? "&#xFFFD;" : "\uFFFD");
                    } else if (o.escapeNonAscii && cp > 0x7F) {
                        sb.append("&#x").append(Integer.toHexString(cp).toUpperCase(Locale.ROOT))
                                .append(';');
                    } else {
                        sb.appendCodePoint(cp);
                    }
            }
        }
        return sb.toString();
    }

    private static String escapeCsv(String s, Options o) {
        if (s.isEmpty()) {
            return s;
        }
        char delimiter = o.csvDelimiter;
        boolean quote = isCsvPadding(s.charAt(0)) || isCsvPadding(s.charAt(s.length() - 1));
        for (int i = 0; i < s.length() && !quote; i++) {
            char c = s.charAt(i);
            quote = c == delimiter || c == '"' || c == '\r' || c == '\n';
        }
        if (!quote) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    private static String escapeSql(String s, Options o) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        if (o.sqlQuoted) {
            sb.append('\'');
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!o.sqlMysql) {
                sb.append(c == '\'' ? "''" : String.valueOf(c));
                continue;
            }
            switch (c) {
                case '\0':
                    sb.append("\\0");
                    break;
                case '\'':
                    sb.append("\\'");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case 0x1A:
                    // Ctrl+Z 在 Windows 上会被当作文件结束符，MySQL 为此专门提供了 \Z
                    sb.append("\\Z");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                default:
                    sb.append(c);
            }
        }
        if (o.sqlQuoted) {
            sb.append('\'');
        }
        return sb.toString();
    }

    private static String escapeRegex(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (REGEX_META.indexOf(c) >= 0) {
                sb.append('\\').append(c);
                continue;
            }
            switch (c) {
                case '\t':
                    sb.append("\\t");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case 0x07:
                    sb.append("\\a");
                    break;
                case 0x1B:
                    sb.append("\\e");
                    break;
                default:
                    if (c < 0x20 || c == 0x7F) {
                        sb.append("\\x").append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** 与 {@code Properties.saveConvert} 一致；区别只在控制字符总是写成 \\uXXXX，避免文件里出现不可见字符。 */
    private static String escapeProperties(String s, Options o) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case ' ':
                    // 值只需转义首个空格（加载时会跳过分隔符后的前导空白）；键里的空格会被当作分隔符，全部要转义
                    if (i == 0 || o.propertiesKey) {
                        sb.append('\\');
                    }
                    sb.append(' ');
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '=':
                case ':':
                case '#':
                case '!':
                case '\\':
                    sb.append('\\').append(c);
                    break;
                default:
                    if (c < 0x20 || c == 0x7F || (o.escapeNonAscii && c > 0x7E)) {
                        appendU(sb, c);
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    // ==========================================
    // 反转义
    // ==========================================

    /** 反转义过程的共享状态：输出缓冲、问题列表与严格/宽松的分流。 */
    private static final class Decoder {
        final String s;
        final Options o;
        final StringBuilder out;
        final List<Issue> issues = new ArrayList<>();

        Decoder(String s, Options o) {
            this.s = s;
            this.o = o;
            this.out = new StringBuilder(s.length());
        }

        /** 严格模式抛出；宽松模式记录下来，由调用方决定怎样原样保留。 */
        void report(Code code, int offset, int length) throws UnescapeException {
            if (!o.lenient) {
                throw new UnescapeException(code, offset, length);
            }
            issues.add(new Issue(code, offset, Math.max(1, length)));
        }

        /**
         * 最常见的宽松处理：只输出引导字符（反斜杠或 &amp;）并从下一个字符继续扫描，
         * 序列的其余部分会作为普通文本被原样输出。
         */
        int problem(Code code, int offset, int length) throws UnescapeException {
            report(code, offset, length);
            out.append(s.charAt(offset));
            return offset + 1;
        }
    }

    private static void unescapeJava(Decoder d, int from, int to) throws UnescapeException {
        String s = d.s;
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            if (c != '\\') {
                d.out.append(c);
                i++;
                continue;
            }
            if (i + 1 >= to) {
                i = d.problem(Code.TRUNCATED_ESCAPE, i, 1);
                continue;
            }
            char next = s.charAt(i + 1);
            char simple = simpleEscape(next);
            if (simple != 0 || next == 's') {
                // \s 是 Java 15 引入的空格转义，主要用于文本块保留行尾空格
                d.out.append(next == 's' ? ' ' : simple);
                i += 2;
            } else if (next >= '0' && next <= '7') {
                i = readOctal(d, i + 1, to);
            } else if (next == 'u') {
                // Java 允许 \\uuuu0041 这种多个 u 的写法（为工具链往返保留的历史设计）
                int j = i + 1;
                while (j < to && s.charAt(j) == 'u') {
                    j++;
                }
                i = readHex4(d, i, j, to);
            } else {
                i = d.problem(Code.BAD_ESCAPE, i, 2);
            }
        }
    }

    private static void unescapeJson(Decoder d, int from, int to) throws UnescapeException {
        String s = d.s;
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            if (c == '"') {
                i = d.problem(Code.UNEXPECTED_QUOTE, i, 1);
                continue;
            }
            if (c < 0x20) {
                // RFC 8259 要求控制字符必须转义；原样出现通常说明文本被截断或拼接错了
                i = d.problem(Code.UNESCAPED_CONTROL, i, 1);
                continue;
            }
            if (c != '\\') {
                d.out.append(c);
                i++;
                continue;
            }
            if (i + 1 >= to) {
                i = d.problem(Code.TRUNCATED_ESCAPE, i, 1);
                continue;
            }
            char next = s.charAt(i + 1);
            switch (next) {
                case '"':
                case '\\':
                case '/':
                    d.out.append(next);
                    i += 2;
                    break;
                case 'b':
                case 'f':
                case 'n':
                case 'r':
                case 't':
                    d.out.append(simpleEscape(next));
                    i += 2;
                    break;
                case 'u':
                    i = readHex4(d, i, i + 2, to);
                    break;
                default:
                    // \' 与 \x41 在 JS 里合法，但 JSON 不认——严格区分才能发现「用 JS 规则生成 JSON」的错误
                    i = d.problem(Code.BAD_ESCAPE, i, 2);
            }
        }
    }

    private static void unescapeJavaScript(Decoder d, int from, int to) throws UnescapeException {
        String s = d.s;
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            if (c != '\\') {
                d.out.append(c);
                i++;
                continue;
            }
            if (i + 1 >= to) {
                i = d.problem(Code.TRUNCATED_ESCAPE, i, 1);
                continue;
            }
            char next = s.charAt(i + 1);
            char simple = simpleEscape(next);
            if (simple != 0) {
                d.out.append(simple);
                i += 2;
            } else if (next == 'v') {
                d.out.append('\u000B');
                i += 2;
            } else if (next == '0' && (i + 2 >= to || !isAsciiDigit(s.charAt(i + 2)))) {
                d.out.append('\0');
                i += 2;
            } else if (next >= '0' && next <= '7') {
                // 附录 B 的遗留八进制转义，非严格模式脚本仍然支持
                i = readOctal(d, i + 1, to);
            } else if (next == 'x') {
                i = readHex(d, i, i + 2, 2, to);
            } else if (next == 'u') {
                if (i + 2 < to && s.charAt(i + 2) == '{') {
                    i = readBracedCodePoint(d, i, i + 3, to);
                } else {
                    i = readHex4(d, i, i + 2, to);
                }
            } else if (next == '\r') {
                // 行延续：反斜杠加换行在 JS 字符串里什么也不产生
                i += 2;
                if (i < to && s.charAt(i) == '\n') {
                    i++;
                }
            } else if (next == '\n' || next == '\u2028' || next == '\u2029') {
                i += 2;
            } else {
                // JS 的「恒等转义」：\q 就是 q
                d.out.append(next);
                i += 2;
            }
        }
    }

    private static void unescapeUnicode(Decoder d) throws UnescapeException {
        String s = d.s;
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n && s.charAt(i + 1) == 'u') {
                int j = i + 1;
                while (j < n && s.charAt(j) == 'u') {
                    j++;
                }
                i = readHex4(d, i, j, n);
            } else {
                d.out.append(c);
                i++;
            }
        }
    }

    /** HTML 与 XML 共用的实体/字符引用解析，差异在命名实体表、非法码点和裸 &amp; 的处理。 */
    private static void unescapeMarkup(Decoder d, boolean xml) throws UnescapeException {
        String s = d.s;
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c != '&') {
                d.out.append(c);
                i++;
                continue;
            }
            int j = i + 1;
            if (j < n && s.charAt(j) == '#') {
                i = readNumericReference(d, i, n, xml);
            } else if (j < n && (xml ? isXmlNameStart(s.charAt(j)) : isAsciiLetter(s.charAt(j)))) {
                int k = j;
                while (k < n && (xml ? isXmlNameChar(s.charAt(k)) : isAsciiLetterOrDigit(s.charAt(k)))) {
                    k++;
                }
                String name = s.substring(j, k);
                int cp = xml ? xmlPredefined(name) : HtmlEntities.lookup(name);
                if (k < n && s.charAt(k) == ';') {
                    if (cp < 0) {
                        // XML 只有 5 个预定义实体，其余都要靠 DTD 声明；HTML 里不认识的名字多半是拼写错误
                        i = d.problem(Code.UNKNOWN_ENTITY, i, k + 1 - i);
                    } else {
                        d.out.appendCodePoint(cp);
                        i = k + 1;
                    }
                } else if (xml || cp >= 0) {
                    i = d.problem(Code.UNTERMINATED_ENTITY, i, k - i);
                } else {
                    // HTML 允许「AT&T」这种与任何实体都不相干的裸 &，照原样输出
                    d.out.append('&');
                    i++;
                }
            } else if (xml) {
                i = d.problem(Code.INVALID_ENTITY, i, 1);
            } else {
                d.out.append('&');
                i++;
            }
        }
    }

    private static int readNumericReference(Decoder d, int amp, int n, boolean xml)
            throws UnescapeException {
        String s = d.s;
        int k = amp + 2;
        boolean hex = k < n && (s.charAt(k) == 'x' || s.charAt(k) == 'X');
        if (hex) {
            k++;
        }
        int digitsStart = k;
        long value = 0;
        while (k < n && (hex ? hexValue(s.charAt(k)) >= 0 : isAsciiDigit(s.charAt(k)))) {
            if (value <= 0x10FFFF) {
                value = value * (hex ? 16 : 10) + (hex ? hexValue(s.charAt(k)) : s.charAt(k) - '0');
            }
            k++;
        }
        if (k == digitsStart) {
            return d.problem(Code.INVALID_ENTITY, amp, k - amp);
        }
        if (k >= n || s.charAt(k) != ';') {
            return d.problem(Code.UNTERMINATED_ENTITY, amp, k - amp);
        }
        int cp;
        if (xml) {
            if (value > 0x10FFFF || !isXmlChar((int) value)) {
                return d.problem(Code.INVALID_CODE_POINT, amp, k + 1 - amp);
            }
            cp = (int) value;
        } else {
            cp = htmlNumericCodePoint(value);
        }
        d.out.appendCodePoint(cp);
        return k + 1;
    }

    /** HTML5 数值引用规则：0、代理项和超范围值一律变成 U+FFFD，C1 区按 Windows-1252 重映射。 */
    private static int htmlNumericCodePoint(long value) {
        if (value == 0 || value > 0x10FFFF || (value >= 0xD800 && value <= 0xDFFF)) {
            return 0xFFFD;
        }
        if (value >= 0x80 && value <= 0x9F) {
            char mapped = WINDOWS_1252_C1[(int) value - 0x80];
            return mapped == 0 ? (int) value : mapped;
        }
        return (int) value;
    }

    private static void unescapeCsv(Decoder d) throws UnescapeException {
        String s = d.s;
        int end = s.length();
        // 从表格或文件里复制单元格时常带上行尾换行；转义结果本身绝不会以裸换行结尾，所以去掉是安全的
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        if (end == 0) {
            return;
        }
        if (s.charAt(0) != '"') {
            // RFC 4180：含引号的字段必须整体加引号
            for (int i = 0; i < end; ) {
                if (s.charAt(i) == '"') {
                    i = d.problem(Code.UNEXPECTED_QUOTE, i, 1);
                } else {
                    d.out.append(s.charAt(i++));
                }
            }
            return;
        }
        int i = 1;
        while (true) {
            if (i >= end) {
                d.report(Code.UNTERMINATED_QUOTE, 0, 1);
                return;
            }
            char c = s.charAt(i);
            if (c != '"') {
                d.out.append(c);
                i++;
            } else if (i + 1 < end && s.charAt(i + 1) == '"') {
                d.out.append('"');
                i += 2;
            } else if (i + 1 == end) {
                return;
            } else if (s.charAt(end - 1) == '"') {
                // 后面还有收尾引号，说明这里是忘了加倍的内部引号
                i = d.problem(Code.UNEXPECTED_QUOTE, i, 1);
            } else {
                d.report(Code.TRAILING_CHARACTERS, i + 1, end - i - 1);
                d.out.append(s, i, end);
                return;
            }
        }
    }

    private static void unescapeSql(Decoder d) throws UnescapeException {
        String s = d.s;
        boolean mysql = d.o.sqlMysql;
        int from = 0;
        int to = s.length();
        if (d.o.sqlQuoted) {
            if (to >= 2 && s.charAt(0) == '\'' && s.charAt(to - 1) == '\''
                    && !(mysql && isBackslashEscaped(s, to - 1))) {
                from = 1;
                to--;
            } else {
                // 宽松模式下把整段当作内容处理
                d.report(Code.MISSING_QUOTE, 0, Math.max(1, to));
            }
        }
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            if (c == '\'') {
                if (i + 1 < to && s.charAt(i + 1) == '\'') {
                    d.out.append('\'');
                    i += 2;
                } else {
                    i = d.problem(Code.UNEXPECTED_QUOTE, i, 1);
                }
            } else if (mysql && c == '\\') {
                if (i + 1 >= to) {
                    i = d.problem(Code.TRUNCATED_ESCAPE, i, 1);
                    continue;
                }
                char next = s.charAt(i + 1);
                switch (next) {
                    case '0':
                        d.out.append('\0');
                        break;
                    case 'b':
                        d.out.append('\b');
                        break;
                    case 'n':
                        d.out.append('\n');
                        break;
                    case 'r':
                        d.out.append('\r');
                        break;
                    case 't':
                        d.out.append('\t');
                        break;
                    case 'Z':
                        d.out.append((char) 0x1A);
                        break;
                    case '%':
                    case '_':
                        // MySQL 的特例：\% 与 \_ 保留反斜杠，供 LIKE 模式使用
                        d.out.append('\\').append(next);
                        break;
                    default:
                        // 其余 \x 在 MySQL 中就是 x（含 \' \" \\）
                        d.out.append(next);
                }
                i += 2;
            } else {
                d.out.append(c);
                i++;
            }
        }
    }

    private static void unescapeRegex(Decoder d) throws UnescapeException {
        String s = d.s;
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c != '\\') {
                d.out.append(c);
                i++;
                continue;
            }
            if (i + 1 >= n) {
                i = d.problem(Code.TRUNCATED_ESCAPE, i, 1);
                continue;
            }
            char next = s.charAt(i + 1);
            if (!isAsciiLetterOrDigit(next)) {
                // java.util.regex 规定：反斜杠加任何非字母数字字符都表示该字符本身
                d.out.append(next);
                i += 2;
                continue;
            }
            switch (next) {
                case 't':
                    d.out.append('\t');
                    i += 2;
                    break;
                case 'n':
                    d.out.append('\n');
                    i += 2;
                    break;
                case 'r':
                    d.out.append('\r');
                    i += 2;
                    break;
                case 'f':
                    d.out.append('\f');
                    i += 2;
                    break;
                case 'a':
                    d.out.append('\u0007');
                    i += 2;
                    break;
                case 'e':
                    d.out.append('\u001B');
                    i += 2;
                    break;
                case '0':
                    if (i + 2 < n && s.charAt(i + 2) >= '0' && s.charAt(i + 2) <= '7') {
                        i = readOctal(d, i + 2, n);
                    } else {
                        i = d.problem(Code.BAD_ESCAPE, i, 2);
                    }
                    break;
                case 'x':
                    if (i + 2 < n && s.charAt(i + 2) == '{') {
                        i = readBracedCodePoint(d, i, i + 3, n);
                    } else {
                        i = readHex(d, i, i + 2, 2, n);
                    }
                    break;
                case 'u':
                    i = readHex4(d, i, i + 2, n);
                    break;
                case 'c':
                    if (i + 2 < n) {
                        d.out.append((char) (s.charAt(i + 2) ^ 64));
                        i += 3;
                    } else {
                        i = d.problem(Code.TRUNCATED_ESCAPE, i, 2);
                    }
                    break;
                case 'Q': {
                    // \Q...\E 引用段整体按字面输出；没有 \E 时一直引用到结尾，与 Pattern 行为一致
                    int close = s.indexOf("\\E", i + 2);
                    int stop = close < 0 ? n : close;
                    d.out.append(s, i + 2, stop);
                    i = close < 0 ? n : close + 2;
                    break;
                }
                default:
                    // \d、\w 这类是字符类而不是字面字符，这段文本并不是一个「转义后的字面量」
                    i = d.problem(Code.BAD_ESCAPE, i, 2);
            }
        }
    }

    private static void unescapeProperties(Decoder d) throws UnescapeException {
        String s = d.s;
        int n = s.length();
        // Properties.load 会跳过每个逻辑行开头的空白，这里保持同样语义
        int i = skipPropertiesWhitespace(s, 0, n);
        while (i < n) {
            char c = s.charAt(i);
            if (c != '\\') {
                d.out.append(c);
                i++;
                continue;
            }
            if (i + 1 >= n) {
                // 文件末尾的孤立反斜杠在 Properties.load 中被视为续行符，直接丢弃
                break;
            }
            char next = s.charAt(i + 1);
            switch (next) {
                case '\r':
                case '\n': {
                    int j = i + 2;
                    if (next == '\r' && j < n && s.charAt(j) == '\n') {
                        j++;
                    }
                    i = skipPropertiesWhitespace(s, j, n);
                    break;
                }
                case 'u':
                    // 与 Java 源码不同，Properties 只接受单个 u
                    i = readHex4(d, i, i + 2, n);
                    break;
                case 't':
                    d.out.append('\t');
                    i += 2;
                    break;
                case 'n':
                    d.out.append('\n');
                    i += 2;
                    break;
                case 'r':
                    d.out.append('\r');
                    i += 2;
                    break;
                case 'f':
                    d.out.append('\f');
                    i += 2;
                    break;
                default:
                    d.out.append(next);
                    i += 2;
            }
        }
    }

    // ==========================================
    // 解析辅助
    // ==========================================

    /**
     * 整段输入被一对引号包住（且结尾引号没有被转义）时只处理引号内的部分，方便直接粘贴源码里的字面量。
     * 转义结果永远不会以裸引号开头，所以这一步不影响往返。
     */
    private static int[] literalBounds(String s, char... quotes) {
        int n = s.length();
        if (n >= 2) {
            char first = s.charAt(0);
            for (char quote : quotes) {
                if (first == quote && s.charAt(n - 1) == quote && !isBackslashEscaped(s, n - 1)) {
                    return new int[]{1, n - 1};
                }
            }
        }
        return new int[]{0, n};
    }

    /** index 处的字符前面是否有奇数个连续反斜杠。 */
    private static boolean isBackslashEscaped(String s, int index) {
        int count = 0;
        for (int k = index - 1; k >= 0 && s.charAt(k) == '\\'; k--) {
            count++;
        }
        return (count & 1) == 1;
    }

    /** 读取 \\uXXXX 的 4 位十六进制；escapeStart 指向反斜杠，digits 指向第一位数字。 */
    private static int readHex4(Decoder d, int escapeStart, int digits, int limit)
            throws UnescapeException {
        return readHex(d, escapeStart, digits, 4, limit);
    }

    private static int readHex(Decoder d, int escapeStart, int digits, int count, int limit)
            throws UnescapeException {
        int value = parseHex(d.s, digits, count, limit);
        if (value == HEX_TRUNCATED) {
            return d.problem(Code.TRUNCATED_UNICODE, escapeStart, limit - escapeStart);
        }
        if (value == HEX_INVALID) {
            int end = digits;
            while (end < limit && end < digits + count && hexValue(d.s.charAt(end)) >= 0) {
                end++;
            }
            return d.problem(Code.INVALID_HEX, escapeStart, Math.min(end + 1, limit) - escapeStart);
        }
        d.out.append((char) value);
        return digits + count;
    }

    /** 解析 \\u{1F600} / \\x{1F600} 形式；start 指向花括号后的第一位。 */
    private static int readBracedCodePoint(Decoder d, int escapeStart, int start, int limit)
            throws UnescapeException {
        String s = d.s;
        int k = start;
        long value = 0;
        while (k < limit && hexValue(s.charAt(k)) >= 0) {
            if (value <= 0x10FFFF) {
                value = value * 16 + hexValue(s.charAt(k));
            }
            k++;
        }
        if (k >= limit) {
            return d.problem(Code.TRUNCATED_UNICODE, escapeStart, limit - escapeStart);
        }
        if (s.charAt(k) != '}' || k == start) {
            return d.problem(Code.INVALID_HEX, escapeStart, k + 1 - escapeStart);
        }
        if (value > 0x10FFFF) {
            return d.problem(Code.INVALID_CODE_POINT, escapeStart, k + 1 - escapeStart);
        }
        d.out.appendCodePoint((int) value);
        return k + 1;
    }

    /** 八进制转义：首位 0-3 时最多 3 位，否则最多 2 位（值不超过 \\377）。start 指向第一位数字。 */
    private static int readOctal(Decoder d, int start, int limit) {
        String s = d.s;
        int max = s.charAt(start) <= '3' ? 3 : 2;
        int value = 0;
        int k = start;
        while (k < limit && k - start < max && s.charAt(k) >= '0' && s.charAt(k) <= '7') {
            value = value * 8 + (s.charAt(k) - '0');
            k++;
        }
        d.out.append((char) value);
        return k;
    }

    private static int parseHex(String s, int start, int count, int limit) {
        int value = 0;
        for (int k = 0; k < count; k++) {
            if (start + k >= limit) {
                return HEX_TRUNCATED;
            }
            int h = hexValue(s.charAt(start + k));
            if (h < 0) {
                return HEX_INVALID;
            }
            value = value * 16 + h;
        }
        return value;
    }

    private static int skipPropertiesWhitespace(String s, int from, int limit) {
        int i = from;
        while (i < limit && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\f')) {
            i++;
        }
        return i;
    }

    /** \b \t \n \f \r \" \' \\ 这组各格式通用的单字符转义；不认识返回 0。 */
    private static char simpleEscape(char c) {
        switch (c) {
            case 'b':
                return '\b';
            case 't':
                return '\t';
            case 'n':
                return '\n';
            case 'f':
                return '\f';
            case 'r':
                return '\r';
            case '"':
                return '"';
            case '\'':
                return '\'';
            case '\\':
                return '\\';
            default:
                return 0;
        }
    }

    private static int xmlPredefined(String name) {
        switch (name) {
            case "amp":
                return '&';
            case "lt":
                return '<';
            case "gt":
                return '>';
            case "quot":
                return '"';
            case "apos":
                return '\'';
            default:
                return -1;
        }
    }

    /** XML 1.0 的 Char 产生式。 */
    private static boolean isXmlChar(int cp) {
        return cp == 0x9 || cp == 0xA || cp == 0xD
                || (cp >= 0x20 && cp <= 0xD7FF)
                || (cp >= 0xE000 && cp <= 0xFFFD)
                || (cp >= 0x10000 && cp <= 0x10FFFF);
    }

    private static boolean isXmlNameStart(char c) {
        return Character.isLetter(c) || c == '_' || c == ':';
    }

    private static boolean isXmlNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == ':' || c == '-' || c == '.';
    }

    private static boolean isControl(char c) {
        return c < 0x20 || (c >= 0x7F && c <= 0x9F);
    }

    private static boolean isCsvPadding(char c) {
        return c == ' ' || c == '\t';
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return isAsciiLetter(c) || isAsciiDigit(c);
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static void appendU(StringBuilder sb, char c) {
        sb.append("\\u")
                .append(HEX[(c >> 12) & 0xF])
                .append(HEX[(c >> 8) & 0xF])
                .append(HEX[(c >> 4) & 0xF])
                .append(HEX[c & 0xF]);
    }
}
