package com.aqishi.toolbox.feature.codec.domain;

import com.aqishi.toolbox.feature.codec.domain.TextEscaper.Options;
import com.aqishi.toolbox.feature.codec.domain.TextEscaper.Result;
import com.aqishi.toolbox.feature.codec.domain.UnescapeException.Code;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextEscaperTest {

    /** 往返测试用的刁钻字符串：空串、控制字符、代理对、中文、引号、反斜杠、脚本结束标签、行分隔符等。 */
    static final List<String> TRICKY = List.of(
            "",
            "plain text",
            "\u0000\u0001\u001f\u007f\u0085",
            "tab\tnl\nret\rff\fbs\b",
            "\uD83D\uDE00 emoji \uD83D\uDC4D\uD83C\uDFFD",
            "中文字符与全角，标点",
            "quotes \" and ' and `",
            "back\\slash \\\\ \\u0041 \\n \\uuuu0041 \\",
            "</script><!-- -->",
            "\u2028\u2029",
            "a & b < c > d",
            "&amp; &#39; &lt; &nbsp",
            "  padded  ",
            "a,b;c\td",
            "=:#! key: value",
            "%_ like \\% \\_",
            ".*+?^$[](){}|\\-",
            "\u00e9\u00fc\u00a0\u00ff\u0100\u00ad",
            "\u001a ctrl-z",
            "\\Q\\E",
            "x\r\n",
            "'",
            "\"",
            "''",
            "\\",
            "\"quoted\"",
            "'single'");

    static Stream<Arguments> roundTripCases() {
        List<Arguments> cases = new ArrayList<>();
        for (EscapeFormat format : EscapeFormat.values()) {
            for (Supplier<Options> options : optionVariants(format)) {
                for (String s : TRICKY) {
                    cases.add(Arguments.of(format, options.get(), s));
                }
            }
        }
        return cases.stream();
    }

    private static List<Supplier<Options>> optionVariants(EscapeFormat format) {
        List<Supplier<Options>> variants = new ArrayList<>();
        switch (format) {
            case JSON:
            case JAVASCRIPT:
                for (boolean nonAscii : new boolean[]{false, true}) {
                    for (boolean scriptSafe : new boolean[]{false, true}) {
                        variants.add(() -> new Options().escapeNonAscii(nonAscii).scriptSafe(scriptSafe));
                    }
                }
                break;
            case CSV:
                for (char delimiter : new char[]{',', ';', '\t'}) {
                    variants.add(() -> new Options().csvDelimiter(delimiter));
                }
                break;
            case SQL:
                for (boolean mysql : new boolean[]{false, true}) {
                    for (boolean quoted : new boolean[]{false, true}) {
                        variants.add(() -> new Options().sqlMysql(mysql).sqlQuoted(quoted));
                    }
                }
                break;
            case PROPERTIES:
                for (boolean key : new boolean[]{false, true}) {
                    for (boolean nonAscii : new boolean[]{false, true}) {
                        variants.add(() -> new Options().propertiesKey(key).escapeNonAscii(nonAscii));
                    }
                }
                break;
            default:
                variants.add(() -> new Options().escapeNonAscii(false));
                variants.add(() -> new Options().escapeNonAscii(true));
        }
        return variants;
    }

    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("roundTripCases")
    void roundTripsEveryTrickyString(EscapeFormat format, Options options, String s) throws Exception {
        Result escaped = TextEscaper.escape(s, format, options);
        String expected = format == EscapeFormat.XML ? replaceXmlIllegal(s) : s;
        assertEquals(expected, TextEscaper.unescape(escaped.text(), format, options).text(),
                () -> "escaped form: " + escaped.text());
        if (format != EscapeFormat.XML) {
            assertFalse(escaped.hasIssues());
        }
    }

    @ParameterizedTest
    @MethodSource("trickyStrings")
    void regexEscapeMatchesLiterally(String s) {
        String pattern = TextEscaper.escape(s, EscapeFormat.REGEX);
        assertTrue(Pattern.compile(pattern).matcher(s).matches(), pattern);
        assertTrue(Pattern.compile("x" + pattern + "y").matcher("x" + s + "y").matches(), pattern);
    }

    @ParameterizedTest
    @MethodSource("trickyStrings")
    void propertiesEscapeLoadsBackThroughJavaUtilProperties(String s) throws IOException {
        for (boolean nonAscii : new boolean[]{false, true}) {
            Options value = new Options().escapeNonAscii(nonAscii);
            Properties loaded = new Properties();
            loaded.load(new StringReader("k=" + TextEscaper.escape(s, EscapeFormat.PROPERTIES, value).text()));
            assertEquals(s, loaded.getProperty("k"));

            Options key = new Options().escapeNonAscii(nonAscii).propertiesKey(true);
            Properties keyed = new Properties();
            keyed.load(new StringReader(TextEscaper.escape(s, EscapeFormat.PROPERTIES, key).text() + "=v"));
            assertEquals("v", keyed.getProperty(s));
        }
    }

    @ParameterizedTest
    @MethodSource("trickyStrings")
    void propertiesEscapeMatchesPropertiesStore(String s) throws IOException {
        Options ascii = new Options().escapeNonAscii(true);
        assertEquals("k=" + TextEscaper.escape(s, EscapeFormat.PROPERTIES, ascii).text(), storedLine("k", s));
        Options key = new Options().escapeNonAscii(true).propertiesKey(true);
        assertEquals(TextEscaper.escape(s, EscapeFormat.PROPERTIES, key).text() + "=v", storedLine(s, "v"));
    }

    static Stream<String> trickyStrings() {
        return TRICKY.stream();
    }

    private static String storedLine(String key, String value) throws IOException {
        Properties properties = new Properties();
        properties.setProperty(key, value);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        properties.store(out, null);
        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1).lines()
                .filter(line -> !line.startsWith("#"))
                .collect(Collectors.joining("\n"));
    }

    private static String replaceXmlIllegal(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(cp -> {
            boolean legal = cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF)
                    || (cp >= 0xE000 && cp <= 0xFFFD) || cp >= 0x10000;
            sb.appendCodePoint(legal ? cp : 0xFFFD);
        });
        return sb.toString();
    }

    private static UnescapeException failure(String input, EscapeFormat format) {
        return failure(input, format, new Options());
    }

    private static UnescapeException failure(String input, EscapeFormat format, Options options) {
        return assertThrows(UnescapeException.class, () -> TextEscaper.unescape(input, format, options));
    }

    private static void assertFailure(String input, EscapeFormat format, Code code, int offset) {
        UnescapeException e = failure(input, format);
        assertEquals(code, e.getCode(), input);
        assertEquals(offset, e.getOffset(), input);
    }

    // ==========================================
    // 各格式的规范细节
    // ==========================================

    @Nested
    class Java {
        @Test
        void escapesControlCharsAndOptionallyNonAscii() {
            assertEquals("a\\tb\\n\\\"c\\\\ d'e\\u0001\\u007F", TextEscaper.escape("a\tb\n\"c\\ d'e\u0001\u007f", EscapeFormat.JAVA));
            assertEquals("中", TextEscaper.escape("中", EscapeFormat.JAVA));
            assertEquals("\\u4E2D\\uD83D\\uDE00",
                    TextEscaper.escape("中\uD83D\uDE00", EscapeFormat.JAVA, new Options().escapeNonAscii(true)).text());
        }

        @Test
        void unescapesOctalSpaceAndMultipleU() throws Exception {
            assertEquals("\0|\n|A|\u00ff| 0|\u0007", TextEscaper.unescape("\\0|\\12|\\101|\\377|\\400|\\7", EscapeFormat.JAVA));
            assertEquals("a b", TextEscaper.unescape("a\\sb", EscapeFormat.JAVA));
            assertEquals("A", TextEscaper.unescape("\\uuuu0041", EscapeFormat.JAVA));
            assertEquals("it's", TextEscaper.unescape("it\\'s", EscapeFormat.JAVA));
        }

        @Test
        void stripsSurroundingQuotesOfAPastedLiteral() throws Exception {
            assertEquals("a\"b", TextEscaper.unescape("\"a\\\"b\"", EscapeFormat.JAVA));
            // 结尾引号被转义时不是包围引号
            assertEquals("\"a\"", TextEscaper.unescape("\"a\\\"", EscapeFormat.JAVA));
        }

        @Test
        void reportsErrorsWithOffsets() {
            assertFailure("ab\\q", EscapeFormat.JAVA, Code.BAD_ESCAPE, 2);
            assertFailure("ab\\", EscapeFormat.JAVA, Code.TRUNCATED_ESCAPE, 2);
            assertFailure("x\\u12", EscapeFormat.JAVA, Code.TRUNCATED_UNICODE, 1);
            assertFailure("x\\u12G4", EscapeFormat.JAVA, Code.INVALID_HEX, 1);
            // 去掉包围引号后，偏移量仍然指向原始输入
            assertFailure("\"ab\\q\"", EscapeFormat.JAVA, Code.BAD_ESCAPE, 3);
        }

        @Test
        void invalidHexLengthCoversTheBadDigit() {
            UnescapeException e = failure("\\u12G4", EscapeFormat.JAVA);
            assertEquals(5, e.getLength());
        }
    }

    @Nested
    class Json {
        @Test
        void escapesPerRfc() {
            assertEquals("\\\"\\\\/\\b\\f\\n\\r\\t\\u0001'", TextEscaper.escape("\"\\/\b\f\n\r\t\u0001'", EscapeFormat.JSON));
            assertEquals("\u2028", TextEscaper.escape("\u2028", EscapeFormat.JSON));
        }

        @Test
        void scriptSafeEscapesClosingTagAndLineSeparators() {
            Options safe = new Options().scriptSafe(true);
            assertEquals("<\\/script>\\u2028\\u2029 a/b",
                    TextEscaper.escape("</script>\u2028\u2029 a/b", EscapeFormat.JSON, safe).text());
        }

        @Test
        void rejectsNonJsonEscapes() {
            assertFailure("it\\'s", EscapeFormat.JSON, Code.BAD_ESCAPE, 2);
            assertFailure("\\x41", EscapeFormat.JSON, Code.BAD_ESCAPE, 0);
            assertFailure("abc\\", EscapeFormat.JSON, Code.TRUNCATED_ESCAPE, 3);
            assertFailure("a\nb", EscapeFormat.JSON, Code.UNESCAPED_CONTROL, 1);
            assertFailure("a\"b", EscapeFormat.JSON, Code.UNEXPECTED_QUOTE, 1);
            assertFailure("\\uu0041", EscapeFormat.JSON, Code.INVALID_HEX, 0);
        }

        @Test
        void acceptsSolidusAndSurrogatePairs() throws Exception {
            assertEquals("a/b\uD83D\uDE00", TextEscaper.unescape("\"a\\/b\\ud83d\\ude00\"", EscapeFormat.JSON));
        }

        @Test
        void lenientModeKeepsBadSequencesAndReportsThem() throws Exception {
            Result result = TextEscaper.unescape("a\\qb\\u12", EscapeFormat.JSON, new Options().lenient(true));
            assertEquals("a\\qb\\u12", result.text());
            assertEquals(2, result.issues().size());
            assertEquals(Code.BAD_ESCAPE, result.issues().get(0).code());
            assertEquals(1, result.issues().get(0).offset());
            assertEquals(Code.TRUNCATED_UNICODE, result.issues().get(1).code());
            assertEquals(4, result.issues().get(1).offset());
        }
    }

    @Nested
    class JavaScript {
        @Test
        void escapesSingleQuoteAndLineSeparators() {
            assertEquals("it\\'s \\\"x\\\" \\u2028", TextEscaper.escape("it's \"x\" \u2028", EscapeFormat.JAVASCRIPT));
        }

        @Test
        void unescapesExtendedForms() throws Exception {
            assertEquals("\uD83D\uDE00", TextEscaper.unescape("\\u{1F600}", EscapeFormat.JAVASCRIPT));
            assertEquals("AB", TextEscaper.unescape("\\x41\\u0042", EscapeFormat.JAVASCRIPT));
            assertEquals("\u000B\0q'", TextEscaper.unescape("\\v\\0\\q\\'", EscapeFormat.JAVASCRIPT));
            assertEquals("ab", TextEscaper.unescape("a\\\r\nb", EscapeFormat.JAVASCRIPT));
            assertEquals("\n", TextEscaper.unescape("\\12", EscapeFormat.JAVASCRIPT));
            assertEquals("x", TextEscaper.unescape("'x'", EscapeFormat.JAVASCRIPT));
        }

        @Test
        void rejectsMalformedCodePoints() {
            assertFailure("\\u{110000}", EscapeFormat.JAVASCRIPT, Code.INVALID_CODE_POINT, 0);
            assertFailure("a\\u{}", EscapeFormat.JAVASCRIPT, Code.INVALID_HEX, 1);
            assertFailure("\\u{1F600", EscapeFormat.JAVASCRIPT, Code.TRUNCATED_UNICODE, 0);
            assertFailure("\\x4", EscapeFormat.JAVASCRIPT, Code.TRUNCATED_UNICODE, 0);
            assertFailure("\\xZZ", EscapeFormat.JAVASCRIPT, Code.INVALID_HEX, 0);
        }
    }

    @Nested
    class Unicode {
        @Test
        void escapesOnlyNonAscii() {
            assertEquals("a\\u4E2D\\n\\uD83D\\uDE00", TextEscaper.escape("a中\\n\uD83D\uDE00", EscapeFormat.UNICODE));
        }

        @Test
        void unescapesOnlyUnicodeSequencesAndJoinsSurrogates() throws Exception {
            String result = TextEscaper.unescape("a\\u4e2db\\uD83D\\uDE00 \\n \\t", EscapeFormat.UNICODE);
            assertEquals("a中b\uD83D\uDE00 \\n \\t", result);
            assertTrue(result.codePoints().anyMatch(cp -> cp == 0x1F600));
        }

        @Test
        void literalBackslashUIsProtected() throws Exception {
            String escaped = TextEscaper.escape("\\u0041", EscapeFormat.UNICODE);
            assertEquals("\\u005Cu0041", escaped);
            assertEquals("\\u0041", TextEscaper.unescape(escaped, EscapeFormat.UNICODE));
        }

        @Test
        void reportsTruncation() {
            assertFailure("ok \\u12", EscapeFormat.UNICODE, Code.TRUNCATED_UNICODE, 3);
        }
    }

    @Nested
    class Html {
        @Test
        void escapesMarkupCharacters() {
            assertEquals("&lt;a href=&quot;x&quot;&gt;Tom &amp; Jerry&#39;s&lt;/a&gt;",
                    TextEscaper.escape("<a href=\"x\">Tom & Jerry's</a>", EscapeFormat.HTML));
            assertEquals("&#20013;&#128512;&#233;",
                    TextEscaper.escape("中\uD83D\uDE00\u00e9", EscapeFormat.HTML, new Options().escapeNonAscii(true)).text());
        }

        @Test
        void unescapesNamedEntities() throws Exception {
            assertEquals("\u00a0\u00a9\u00ae\u2122\u2026\u2014\u2013\u2018\u2019\u201c\u201d\u20ac\u00a5\u00d7\u00f7"
                            + "\u00b7\u00ab\u00bb\u00b0\u00b1\u00b5\u00b6\u00a7\u03b1\u03a9\u00ff'",
                    TextEscaper.unescape("&nbsp;&copy;&reg;&trade;&hellip;&mdash;&ndash;&lsquo;&rsquo;&ldquo;&rdquo;"
                            + "&euro;&yen;&times;&divide;&middot;&laquo;&raquo;&deg;&plusmn;&micro;&para;&sect;"
                            + "&alpha;&Omega;&yuml;&apos;", EscapeFormat.HTML));
        }

        @Test
        void unescapesNumericReferencesAndSanitisesInvalidOnes() throws Exception {
            assertEquals("ABC\uD83D\uDE00", TextEscaper.unescape("&#65;&#x42;&#X43;&#128512;", EscapeFormat.HTML));
            assertEquals("\uFFFD\uFFFD\uFFFD\uFFFD", TextEscaper.unescape("&#0;&#x110000;&#xD800;&#99999999999;", EscapeFormat.HTML));
            assertEquals("\u20ac\u2122\u0081", TextEscaper.unescape("&#128;&#x99;&#129;", EscapeFormat.HTML));
        }

        @Test
        void bareAmpersandIsText() throws Exception {
            assertEquals("AT&T & co", TextEscaper.unescape("AT&T & co", EscapeFormat.HTML));
        }

        @Test
        void reportsMalformedEntities() {
            assertFailure("x &amp y", EscapeFormat.HTML, Code.UNTERMINATED_ENTITY, 2);
            assertFailure("&foo;", EscapeFormat.HTML, Code.UNKNOWN_ENTITY, 0);
            assertFailure("a&#;", EscapeFormat.HTML, Code.INVALID_ENTITY, 1);
            assertFailure("&#65", EscapeFormat.HTML, Code.UNTERMINATED_ENTITY, 0);
            UnescapeException e = failure("&foo;", EscapeFormat.HTML);
            assertEquals(5, e.getLength());
        }

        @Test
        void entityTableCoversHtml4() {
            assertEquals(253, HtmlEntities.size());
            assertEquals(0xA0, HtmlEntities.lookup("nbsp"));
            assertEquals(0xFF, HtmlEntities.lookup("yuml"));
            assertEquals(0x3A9, HtmlEntities.lookup("Omega"));
            assertEquals(0x3C9, HtmlEntities.lookup("omega"));
            assertEquals(0x2666, HtmlEntities.lookup("diams"));
            assertEquals(0x2660, HtmlEntities.lookup("spades"));
            assertEquals(0x22C5, HtmlEntities.lookup("sdot"));
            assertEquals(-1, HtmlEntities.lookup("NBSP"));
        }
    }

    @Nested
    class Xml {
        @Test
        void escapesPredefinedEntities() {
            assertEquals("&lt;a b=&quot;1&quot; c=&apos;2&apos;&gt;&amp;&#xD;\n",
                    TextEscaper.escape("<a b=\"1\" c='2'>&\r\n", EscapeFormat.XML));
            assertEquals("&#x4E2D;&#x1F600;",
                    TextEscaper.escape("中\uD83D\uDE00", EscapeFormat.XML, new Options().escapeNonAscii(true)).text());
        }

        @Test
        void replacesIllegalCharactersWithWarning() {
            Result result = TextEscaper.escape("a\u0001b\uFFFEc", EscapeFormat.XML, new Options());
            assertEquals("a\uFFFDb\uFFFDc", result.text());
            assertEquals(2, result.issues().size());
            assertEquals(Code.XML_ILLEGAL_CHAR, result.issues().get(0).code());
            assertEquals(1, result.issues().get(0).offset());
            assertEquals(3, result.issues().get(1).offset());
        }

        @Test
        void unescapesPredefinedAndNumeric() throws Exception {
            assertEquals("<>&\"'A\uD83D\uDE00", TextEscaper.unescape("&lt;&gt;&amp;&quot;&apos;&#65;&#x1F600;", EscapeFormat.XML));
        }

        @Test
        void rejectsWhatXmlRejects() {
            assertFailure("a&nbsp;", EscapeFormat.XML, Code.UNKNOWN_ENTITY, 1);
            assertFailure("a & b", EscapeFormat.XML, Code.INVALID_ENTITY, 2);
            assertFailure("&#1;", EscapeFormat.XML, Code.INVALID_CODE_POINT, 0);
            assertFailure("&#x110000;", EscapeFormat.XML, Code.INVALID_CODE_POINT, 0);
            assertFailure("&amp", EscapeFormat.XML, Code.UNTERMINATED_ENTITY, 0);
        }

        @Test
        void lenientKeepsUnknownEntity() throws Exception {
            Result result = TextEscaper.unescape("&nbsp;&amp;", EscapeFormat.XML, new Options().lenient(true));
            assertEquals("&nbsp;&", result.text());
            assertEquals(Code.UNKNOWN_ENTITY, result.issues().get(0).code());
        }
    }

    @Nested
    class Csv {
        @Test
        void quotesOnlyWhenNeeded() {
            assertEquals("abc", TextEscaper.escape("abc", EscapeFormat.CSV));
            assertEquals("\"a,b\"", TextEscaper.escape("a,b", EscapeFormat.CSV));
            assertEquals("\"a\"\"b\"", TextEscaper.escape("a\"b", EscapeFormat.CSV));
            assertEquals("\"a\nb\"", TextEscaper.escape("a\nb", EscapeFormat.CSV));
            assertEquals("\" a\"", TextEscaper.escape(" a", EscapeFormat.CSV));
            assertEquals("\"a \"", TextEscaper.escape("a ", EscapeFormat.CSV));
            assertEquals("", TextEscaper.escape("", EscapeFormat.CSV));
        }

        @Test
        void delimiterIsConfigurable() {
            assertEquals("a,b", TextEscaper.escape("a,b", EscapeFormat.CSV, new Options().csvDelimiter(';')).text());
            assertEquals("\"a;b\"", TextEscaper.escape("a;b", EscapeFormat.CSV, new Options().csvDelimiter(';')).text());
            assertEquals("\"a\tb\"", TextEscaper.escape("a\tb", EscapeFormat.CSV, new Options().csvDelimiter('\t')).text());
        }

        @Test
        void unescapesQuotedField() throws Exception {
            assertEquals("a\"b,c", TextEscaper.unescape("\"a\"\"b,c\"", EscapeFormat.CSV));
            assertEquals("plain", TextEscaper.unescape("plain\r\n", EscapeFormat.CSV));
            assertEquals("x", TextEscaper.unescape("\"x\"\n", EscapeFormat.CSV));
        }

        @Test
        void reportsMalformedFields() {
            assertFailure("\"abc", EscapeFormat.CSV, Code.UNTERMINATED_QUOTE, 0);
            assertFailure("\"a\"b", EscapeFormat.CSV, Code.TRAILING_CHARACTERS, 3);
            assertFailure("a\"b", EscapeFormat.CSV, Code.UNEXPECTED_QUOTE, 1);
            assertFailure("\"a\"b\"", EscapeFormat.CSV, Code.UNEXPECTED_QUOTE, 2);
        }
    }

    @Nested
    class Sql {
        @Test
        void ansiDoublesSingleQuotes() {
            assertEquals("it''s \\n", TextEscaper.escape("it's \\n", EscapeFormat.SQL));
            assertEquals("'it''s'", TextEscaper.escape("it's", EscapeFormat.SQL, new Options().sqlQuoted(true)).text());
        }

        @Test
        void mysqlUsesBackslashes() {
            Options mysql = new Options().sqlMysql(true);
            assertEquals("it\\'s \\\"q\\\" \\\\ \\n\\r\\t\\0\\Z\\b %_",
                    TextEscaper.escape("it's \"q\" \\ \n\r\t\0\u001a\b %_", EscapeFormat.SQL, mysql).text());
        }

        @Test
        void unescapesBothModes() throws Exception {
            assertEquals("it's", TextEscaper.unescape("'it''s'", EscapeFormat.SQL, new Options().sqlQuoted(true)).text());
            Options mysql = new Options().sqlMysql(true).sqlQuoted(true);
            assertEquals("it's\n\\%x", TextEscaper.unescape("'it\\'s\\n\\%\\x'", EscapeFormat.SQL, mysql).text());
            assertEquals("a'b", TextEscaper.unescape("'a''b'", EscapeFormat.SQL, mysql).text());
        }

        @Test
        void reportsQuoteProblems() {
            assertFailure("it's", EscapeFormat.SQL, Code.UNEXPECTED_QUOTE, 2);
            UnescapeException missing = failure("abc", EscapeFormat.SQL, new Options().sqlQuoted(true));
            assertEquals(Code.MISSING_QUOTE, missing.getCode());
            UnescapeException truncated = failure("ab\\", EscapeFormat.SQL, new Options().sqlMysql(true));
            assertEquals(Code.TRUNCATED_ESCAPE, truncated.getCode());
            assertEquals(2, truncated.getOffset());
        }
    }

    @Nested
    class Regex {
        @Test
        void escapesMetacharacters() {
            assertEquals("a\\.b\\*\\(c\\)\\[d\\]\\{1\\}\\|\\^\\$\\?\\+\\\\ \\t\\x01",
                    TextEscaper.escape("a.b*(c)[d]{1}|^$?+\\ \t\u0001", EscapeFormat.REGEX));
        }

        @Test
        void unescapesLiteralPatterns() throws Exception {
            assertEquals("a.b-c", TextEscaper.unescape("a\\.b\\-c", EscapeFormat.REGEX));
            assertEquals("x.*y", TextEscaper.unescape("\\Qx.*y\\E", EscapeFormat.REGEX));
            assertEquals("AB\n\u0001", TextEscaper.unescape("\\x41\\u0042\\012\\cA", EscapeFormat.REGEX));
        }

        @Test
        void characterClassesAreNotLiterals() {
            assertFailure("a\\d+", EscapeFormat.REGEX, Code.BAD_ESCAPE, 1);
        }
    }

    @Nested
    class PropertiesFormat {
        @Test
        void escapesSeparatorsAndLeadingSpace() {
            assertEquals("\\ a b\\=c\\:d\\#e\\!f", TextEscaper.escape(" a b=c:d#e!f", EscapeFormat.PROPERTIES));
            assertEquals("\\ a\\ b", TextEscaper.escape(" a b", EscapeFormat.PROPERTIES, new Options().propertiesKey(true)).text());
            assertEquals("\\u4E2D", TextEscaper.escape("中", EscapeFormat.PROPERTIES, new Options().escapeNonAscii(true)).text());
        }

        @Test
        void unescapesContinuationLines() throws Exception {
            // 续行后下一行的前导空白会被吃掉，与 Properties.load 一致
            assertEquals("abc", TextEscaper.unescape("a\\\n   b\\\r\n\t c", EscapeFormat.PROPERTIES));
            assertEquals("x", TextEscaper.unescape("   x\\", EscapeFormat.PROPERTIES));
            assertEquals("q:\u4e2d", TextEscaper.unescape("\\q\\:\\u4e2d", EscapeFormat.PROPERTIES));
        }

        @Test
        void rejectsMalformedUnicode() {
            assertFailure("a\\uZZZZ", EscapeFormat.PROPERTIES, Code.INVALID_HEX, 1);
            assertFailure("\\u00", EscapeFormat.PROPERTIES, Code.TRUNCATED_UNICODE, 0);
        }
    }

    @Test
    void nullInputIsTreatedAsEmpty() throws Exception {
        for (EscapeFormat format : EscapeFormat.values()) {
            assertEquals("", TextEscaper.escape(null, format, null).text());
            assertEquals("", TextEscaper.unescape(null, format, null).text());
        }
    }

    @Test
    void loneSurrogatesSurviveTheFormatsThatCanCarryThem() throws Exception {
        String lone = "a\uD800b\uDC00";
        for (EscapeFormat format : new EscapeFormat[]{EscapeFormat.JAVA, EscapeFormat.JSON,
                EscapeFormat.UNICODE, EscapeFormat.HTML, EscapeFormat.PROPERTIES}) {
            Options options = new Options().escapeNonAscii(true);
            String escaped = TextEscaper.escape(lone, format, options).text();
            assertEquals(lone, TextEscaper.unescape(escaped, format, options).text(), format.name());
        }
    }
}
