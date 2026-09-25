package com.aqishi.toolbox.feature.codec.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamingConverterTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "fooBar|foo bar",
            "FooBar|foo bar",
            "foo_bar|foo bar",
            "FOO_BAR|foo bar",
            "foo-bar|foo bar",
            "foo.bar|foo bar",
            "foo/bar|foo bar",
            "'  foo   bar  '|foo bar",
            "Foo Bar Baz|foo bar baz",
            "HTTPServerError|http server error",
            "getHTTPResponseCode|get http response code",
            "XMLHttpRequest|xml http request",
            "parseURL|parse url",
            "userID|user id",
            "IPv6Address|ipv6 address",
            "getIPv4|get ipv4",
            "OAuth2Token|oauth2 token",
            "iOSVersion|ios version",
            "utf8Decoder|utf8 decoder",
            "version2Name|version2 name",
            "HTTP2Server|http2 server",
            "sha256Hash|sha256 hash",
            "2FACode|2fa code",
            "item_2|item 2",
            "ABC|abc",
            "ABCd|ab cd",
            "aB|a b",
            "A|a",
            "__init__|init",
            "mixed_caseWith-Separators.andMore|mixed case with separators and more",
            "SCREAMING_SNAKE_CASE|screaming snake case",
            "Train-Case-Name|train case name",
            "path/to/some_file|path to some file",
            "用户Name|用户 name",
            "getUser名称|get user 名称",
            "straßeName|straße name",
            "ÉcoleNormale|école normale",
            "naïveBayes|naïve bayes",
            "a1b2C3|a1b2 c3",
            "foo$bar:baz|foo bar baz"
    })
    void splitsIdentifiers(String input, String expectedWords) {
        List<String> words = NamingConverter.split(input);
        List<String> lowered = words.stream().map(w -> w.toLowerCase(java.util.Locale.ROOT)).toList();
        assertEquals(Arrays.asList(expectedWords.split(" ")), lowered);
    }

    @Test
    void emptyAndSeparatorOnlyInputsYieldNoWords() {
        assertTrue(NamingConverter.split("").isEmpty());
        assertTrue(NamingConverter.split(null).isEmpty());
        assertTrue(NamingConverter.split(" _-./ ").isEmpty());
        for (NamingStyle style : NamingStyle.values()) {
            assertEquals("", NamingConverter.convert("__", style, false));
        }
    }

    @Test
    void convertsToEveryStyle() {
        Map<NamingStyle, String> expected = new EnumMap<>(NamingStyle.class);
        expected.put(NamingStyle.CAMEL, "httpServerError2");
        expected.put(NamingStyle.PASCAL, "HttpServerError2");
        expected.put(NamingStyle.SNAKE, "http_server_error2");
        expected.put(NamingStyle.SCREAMING_SNAKE, "HTTP_SERVER_ERROR2");
        expected.put(NamingStyle.KEBAB, "http-server-error2");
        expected.put(NamingStyle.SCREAMING_KEBAB, "HTTP-SERVER-ERROR2");
        expected.put(NamingStyle.TRAIN, "Http-Server-Error2");
        expected.put(NamingStyle.DOT, "http.server.error2");
        expected.put(NamingStyle.PATH, "http/server/error2");
        expected.put(NamingStyle.TITLE, "Http Server Error2");
        expected.put(NamingStyle.SENTENCE, "Http server error2");
        expected.put(NamingStyle.SPACE, "http server error2");
        expected.put(NamingStyle.FLAT, "httpservererror2");
        expected.put(NamingStyle.UPPER_FLAT, "HTTPSERVERERROR2");
        assertEquals(NamingStyle.values().length, expected.size());
        for (Map.Entry<NamingStyle, String> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), NamingConverter.convert("HTTPServerError2", entry.getKey(), false),
                    entry.getKey().name());
        }
    }

    @Test
    void keepsKnownAcronymsWhenRequested() {
        assertEquals("HTTPServer", NamingConverter.convert("http_server", NamingStyle.PASCAL, true));
        assertEquals("HttpServer", NamingConverter.convert("http_server", NamingStyle.PASCAL, false));
        // camelCase 的首词始终小写
        assertEquals("httpServerID", NamingConverter.convert("HTTP_SERVER_ID", NamingStyle.CAMEL, true));
        assertEquals("parseJSONFromURL", NamingConverter.convert("parse_json_from_url", NamingStyle.CAMEL, true));
        assertEquals("IPv6Address", NamingConverter.convert("ipv6_address", NamingStyle.PASCAL, true));
        assertEquals("UTF8Decoder", NamingConverter.convert("utf8-decoder", NamingStyle.PASCAL, true));
        assertEquals("SHA256 Hash", NamingConverter.convert("sha256Hash", NamingStyle.TITLE, true));
        assertEquals("User ID", NamingConverter.convert("userId", NamingStyle.SENTENCE, true));
        // 与大小写无关的风格不受影响
        assertEquals("http_server", NamingConverter.convert("HTTPServer", NamingStyle.SNAKE, true));
    }

    @Test
    void acronymLookupStripsTrailingDigits() {
        assertEquals("MD5", NamingConverter.canonicalAcronym("md5"));
        assertEquals("OAuth2", NamingConverter.canonicalAcronym("oauth2"));
        assertEquals("K8S", NamingConverter.canonicalAcronym("k8s"));
        assertEquals(null, NamingConverter.canonicalAcronym("server"));
        assertEquals(null, NamingConverter.canonicalAcronym("123"));
    }

    @Test
    void alreadyStyledInputConvertsIdempotently() {
        for (NamingStyle style : NamingStyle.values()) {
            if (style == NamingStyle.FLAT || style == NamingStyle.UPPER_FLAT) {
                // 全小写/全大写无分隔拼接会丢失词边界，无法再切回
                continue;
            }
            String once = NamingConverter.convert("getHTTPResponseCode", style, false);
            assertEquals(once, NamingConverter.convert(once, style, false), style.name());
            assertEquals("get_http_response_code", NamingConverter.convert(once, NamingStyle.SNAKE, false),
                    style.name());
        }
    }

    @Test
    void nonAsciiCaseMappingUsesRootLocale() {
        assertEquals("STRASSE_NAME", NamingConverter.convert("straßeName", NamingStyle.SCREAMING_SNAKE, false));
        assertEquals("ÉcoleNormale", NamingConverter.convert("école normale", NamingStyle.PASCAL, false));
        assertEquals("用户_name", NamingConverter.convert("用户Name", NamingStyle.SNAKE, false));
        assertEquals("userId", NamingConverter.convert("USER_ID", NamingStyle.CAMEL, false));
    }

    @Test
    void everyStyleHasASample() {
        for (NamingStyle style : NamingStyle.values()) {
            assertTrue(!style.getSample().isEmpty());
        }
    }
}
