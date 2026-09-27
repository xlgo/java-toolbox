package com.aqishi.toolbox.feature.system.infra;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleCharsetsTest {

    @Test
    void parsesChcpOutputInAnyLanguage() {
        assertEquals(OptionalInt.of(936), ConsoleCharsets.parseCodePage("活动代码页: 936\r\n"));
        assertEquals(OptionalInt.of(437), ConsoleCharsets.parseCodePage("Active code page: 437"));
        assertEquals(OptionalInt.of(850), ConsoleCharsets.parseCodePage("Aktive Codepage: 850."));
        assertEquals(OptionalInt.of(65001), ConsoleCharsets.parseCodePage("Active code page: 65001"));
        assertTrue(ConsoleCharsets.parseCodePage("no number").isEmpty());
        assertTrue(ConsoleCharsets.parseCodePage(null).isEmpty());
    }

    @Test
    void mapsCodePagesToCharsets() {
        assertEquals(Charset.forName("GBK"), ConsoleCharsets.forCodePage(936));
        assertEquals(StandardCharsets.UTF_8, ConsoleCharsets.forCodePage(65001));
        assertNotNull(ConsoleCharsets.forCodePage(437));
        assertNotNull(ConsoleCharsets.forCodePage(850));
        assertNotNull(ConsoleCharsets.forCodePage(1252));
        assertEquals(null, ConsoleCharsets.forCodePage(1));
    }

    @Test
    void fallsBackThroughSystemProperties() {
        Map<String, String> props = Map.of("native.encoding", "GBK");
        assertEquals(Charset.forName("GBK"), ConsoleCharsets.fromProperties(props::get));
        Map<String, String> console = Map.of("stdout.encoding", "IBM850", "native.encoding", "windows-1252");
        assertEquals(Charset.forName("IBM850"), ConsoleCharsets.fromProperties(console::get));
        Map<String, String> bogus = Map.of("sun.stdout.encoding", "no-such-charset");
        assertEquals(Charset.defaultCharset(), ConsoleCharsets.fromProperties(bogus::get));
    }

    @Test
    void decodesGbkNetstatOutputWithConsoleCharset() {
        String text = "\r\n活动连接\r\n\r\n  协议  本地地址          外部地址        状态           PID\r\n";
        byte[] gbk = text.getBytes(Charset.forName("GBK"));
        assertEquals(text, ConsoleCharsets.decode(gbk, Charset.forName("GBK")));
    }

    @Test
    void prefersValidUtf8AndDetectsUtf16() {
        String text = "C:\\用户\\app.exe --port 8080";
        assertEquals(text, ConsoleCharsets.decode(text.getBytes(StandardCharsets.UTF_8), Charset.forName("GBK")));

        byte[] utf16 = ("CommandLine  \r\n" + text).getBytes(StandardCharsets.UTF_16LE);
        assertEquals("CommandLine  \r\n" + text, ConsoleCharsets.decode(utf16, Charset.forName("GBK")));

        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'o', 'k'};
        assertEquals("ok", ConsoleCharsets.decode(bom, null));

        // 德文 netstat 的 ABHÖREN 在 CP850 里是 0x99，不是合法 UTF-8。
        byte[] cp850 = "ABHÖREN".getBytes(Charset.forName("IBM850"));
        assertEquals("ABHÖREN", ConsoleCharsets.decode(cp850, Charset.forName("IBM850")));
        assertEquals("", ConsoleCharsets.decode(new byte[0], null));
    }
}
