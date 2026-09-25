package com.aqishi.toolbox.feature.network.domain;

import com.aqishi.toolbox.util.ShellQuote;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurlCommandTest {

    /** 导出再导入必须还原出同一个请求，包括引号、$、反引号、换行这些最容易出错的字符。 */
    @Test
    void exportedCommandParsesBackToTheSameRequest() {
        CurlCommand original = new CurlCommand("POST", "https://api.test/items?q=$HOME&x=`id`",
                List.of("Content-Type: application/json", "X-Note: it's \"quoted\""),
                "{\n  \"name\": \"O'Brien\",\n  \"cost\": \"$5\"\n}");

        CurlCommand parsed = CurlCommand.parse(original.toShell());

        assertEquals(original.method(), parsed.method());
        assertEquals(original.url(), parsed.url());
        assertEquals(original.headers(), parsed.headers());
        assertEquals(original.body(), parsed.body());
    }

    /** 回归：旧导出把 URL 放进双引号，$( ) 与反引号在粘贴到终端时会被执行。 */
    @Test
    void exportNeverLeavesShellExpansionUnquoted() {
        String shell = new CurlCommand("GET", "https://x.test/?a=$(rm -rf ~)", List.of(), "").toShell();

        assertTrue(shell.contains(ShellQuote.single("https://x.test/?a=$(rm -rf ~)")), shell);
    }

    @Test
    void parsesChromeCopyAsCurlStyle() {
        String command = "curl 'https://example.com/api' \\\n"
                + "  -H 'accept: application/json' \\\n"
                + "  -H 'authorization: Bearer abc' \\\n"
                + "  --data-raw '{\"a\":1}' \\\n"
                + "  --compressed";

        CurlCommand parsed = CurlCommand.parse(command);

        assertEquals("POST", parsed.method());
        assertEquals("https://example.com/api", parsed.url());
        assertEquals(List.of("accept: application/json", "authorization: Bearer abc"), parsed.headers());
        assertEquals("{\"a\":1}", parsed.body());
    }

    @Test
    void parsesDoubleQuotesAndAnsiCQuotes() {
        CurlCommand parsed = CurlCommand.parse(
                "curl -X PUT \"https://x.test/a b\" -H \"X-Q: say \\\"hi\\\"\" --data-raw $'line1\\nline2'");

        assertEquals("PUT", parsed.method());
        assertEquals("https://x.test/a b", parsed.url());
        assertEquals(List.of("X-Q: say \"hi\""), parsed.headers());
        assertEquals("line1\nline2", parsed.body());
    }

    @Test
    void mapsConvenienceOptionsToHeaders() {
        CurlCommand parsed = CurlCommand.parse(
                "curl -A 'agent/1' -e https://ref.test -b 'k=v' -u user:pw https://x.test -o out.txt -L");

        assertEquals("GET", parsed.method());
        assertEquals("https://x.test", parsed.url());
        assertEquals(List.of("User-Agent: agent/1", "Referer: https://ref.test", "Cookie: k=v",
                "Authorization: Basic dXNlcjpwdw=="), parsed.headers());
    }

    @Test
    void joinsRepeatedDataWithAmpersand() {
        assertEquals("a=1&b=2", CurlCommand.parse("curl https://x.test -d a=1 -d b=2").body());
    }

    @Test
    void headOptionSetsMethod() {
        assertEquals("HEAD", CurlCommand.parse("curl -I https://x.test").method());
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> CurlCommand.parse("wget https://x.test"));
        assertThrows(IllegalArgumentException.class, () -> CurlCommand.parse("curl -H 'a: b'"));
        assertThrows(IllegalArgumentException.class, () -> CurlCommand.parse("curl 'https://x.test"));
        assertThrows(IllegalArgumentException.class, () -> CurlCommand.parse("curl https://x.test -H"));
    }
}
