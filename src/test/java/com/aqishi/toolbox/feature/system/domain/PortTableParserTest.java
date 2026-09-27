package com.aqishi.toolbox.feature.system.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortTableParserTest {

    static String fixture(String name) throws IOException {
        try (InputStream in = PortTableParserTest.class.getResourceAsStream("/portprocess/" + name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static PortEntry find(List<PortEntry> entries, PortEntry.Protocol protocol, String address, int port) {
        return entries.stream()
                .filter(e -> e.protocol() == protocol && e.localAddress().equals(address) && e.localPort() == port)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + protocol + " " + address + ":" + port
                        + " in " + entries));
    }

    // ==========================================
    // Windows netstat
    // ==========================================

    @Test
    void parsesChineseLocalizedWindowsNetstatByStructure() throws IOException {
        List<PortEntry> entries = PortTableParser.parseWindowsNetstat(fixture("netstat-windows-zh.txt"));

        // 中文表头「活动连接」「协议 本地地址 ...」必须被跳过，数据行一条不少。
        assertEquals(12, entries.size());

        PortEntry rpc = find(entries, PortEntry.Protocol.TCP, "0.0.0.0", 135);
        assertEquals("LISTEN", rpc.state());
        assertEquals(1492, rpc.pid());
        assertEquals("0.0.0.0", rpc.remoteAddress());
        assertEquals(0, rpc.remotePort());
        assertTrue(rpc.isListening());

        PortEntry established = find(entries, PortEntry.Protocol.TCP, "127.0.0.1", 5432);
        assertEquals("ESTABLISHED", established.state());
        assertEquals("127.0.0.1", established.remoteAddress());
        assertEquals(61234, established.remotePort());
        assertFalse(established.isListening());

        PortEntry ipv6 = find(entries, PortEntry.Protocol.TCP, "::", 135);
        assertEquals("[::]:135", ipv6.localEndpoint());
        assertTrue(ipv6.isIpv6());

        PortEntry linkLocal = find(entries, PortEntry.Protocol.TCP, "fe80::1c2d:3e4f:5a6b:7c8d%12", 7680);
        assertEquals("fe80::9:8:7:6%12", linkLocal.remoteAddress());
        assertEquals(51000, linkLocal.remotePort());
        assertEquals(9120, linkLocal.pid());

        PortEntry ntp = find(entries, PortEntry.Protocol.UDP, "0.0.0.0", 123);
        assertEquals("", ntp.state());
        assertEquals("*", ntp.remoteAddress());
        assertEquals(PortEntry.NO_PORT, ntp.remotePort());
        assertEquals(2468, ntp.pid());
        assertTrue(ntp.isListening());
        assertEquals("*:*", ntp.remoteEndpoint());

        PortEntry mdns = find(entries, PortEntry.Protocol.UDP, "fe80::1%12", 5353);
        assertEquals("[fe80::1%12]:5353", mdns.localEndpoint());

        PortEntry timeWait = find(entries, PortEntry.Protocol.TCP, "192.168.1.20", 52345);
        assertEquals("TIME_WAIT", timeWait.state());
        assertEquals(0, timeWait.pid());
    }

    @Test
    void parsesEnglishWindowsNetstat() throws IOException {
        List<PortEntry> entries = PortTableParser.parseWindowsNetstat(fixture("netstat-windows-en.txt"));

        assertEquals(7, entries.size());
        assertEquals("CLOSE_WAIT", find(entries, PortEntry.Protocol.TCP, "10.0.0.5", 49870).state());
        assertEquals(5560, find(entries, PortEntry.Protocol.UDP, "::1", 1900).pid());
        assertEquals(4, find(entries, PortEntry.Protocol.TCP, "::", 445).pid());
    }

    @Test
    void mapsGermanStatesToCanonicalNames() throws IOException {
        List<PortEntry> entries = PortTableParser.parseWindowsNetstat(fixture("netstat-windows-de.txt"));

        assertEquals(5, entries.size());
        assertEquals("LISTEN", find(entries, PortEntry.Protocol.TCP, "0.0.0.0", 135).state());
        assertEquals("ESTABLISHED", find(entries, PortEntry.Protocol.TCP, "192.168.178.20", 50433).state());
        assertEquals("TIME_WAIT", find(entries, PortEntry.Protocol.TCP, "192.168.178.20", 50501).state());
        assertEquals("CLOSE_WAIT", find(entries, PortEntry.Protocol.TCP, "192.168.178.20", 50502).state());
    }

    @Test
    void acceptsMultiWordLocalizedStateAndMissingPidColumn() {
        String text = "  TCP    0.0.0.0:80     0.0.0.0:0      IN ASCOLTO      4242\n"
                + "  TCP    0.0.0.0:81     0.0.0.0:0      LISTENING\n"
                + "  UDP    0.0.0.0:82     *:*\n";
        List<PortEntry> entries = PortTableParser.parseWindowsNetstat(text);

        assertEquals(3, entries.size());
        assertEquals("LISTEN", entries.get(0).state());
        assertEquals(4242, entries.get(0).pid());
        assertEquals("LISTEN", entries.get(1).state());
        assertEquals(PortEntry.NO_PID, entries.get(1).pid());
        assertEquals(PortEntry.NO_PID, entries.get(2).pid());
    }

    @Test
    void parsesTasklistCsvWithQuotedLocalizedMemoryColumn() throws IOException {
        Map<Long, String> names = PortTableParser.parseTasklistCsv(fixture("tasklist.csv"));

        assertEquals(8, names.size());
        assertEquals("System Idle Process", names.get(0L));
        assertEquals("svchost.exe", names.get(1492L));
        assertEquals("java.exe", names.get(23456L));
        assertEquals("My \"Quoted\", App.exe", names.get(9120L));
        assertEquals("服务主机.exe", names.get(3344L));
    }

    @Test
    void attachesTasklistNamesToNetstatRows() throws IOException {
        List<PortEntry> entries = PortTableParser.attachProcessNames(
                PortTableParser.parseWindowsNetstat(fixture("netstat-windows-zh.txt")),
                PortTableParser.parseTasklistCsv(fixture("tasklist.csv")));

        assertEquals("java.exe", find(entries, PortEntry.Protocol.TCP, "0.0.0.0", 8080).processName());
        assertEquals("postgres.exe", find(entries, PortEntry.Protocol.TCP, "127.0.0.1", 5432).processName());
        // 1060 不在 tasklist 里（查询间隙退出了）：保持空名而不是报错。
        assertEquals("", find(entries, PortEntry.Protocol.TCP, "::1", 49664).processName());
    }

    // ==========================================
    // lsof
    // ==========================================

    @Test
    void parsesMacLsofWithEscapesAndDeduplicates() throws IOException {
        List<PortEntry> entries = PortTableParser.parseLsof(fixture("lsof-mac.txt"));

        // launchd 的 IPv4/IPv6 两行都显示成 *:22、postgres 重复的一行：各合并为一条。
        assertEquals(1, entries.stream().filter(e -> e.pid() == 1).count());
        assertEquals(2, entries.stream().filter(e -> e.pid() == 812).count());

        PortEntry chrome = entries.stream().filter(e -> e.pid() == 1234).findFirst().orElseThrow();
        assertEquals("Google Chrome Helper", chrome.processName());
        assertEquals("192.168.1.10", chrome.localAddress());
        assertEquals(52345, chrome.localPort());
        assertEquals("142.250.72.14", chrome.remoteAddress());
        assertEquals(443, chrome.remotePort());
        assertEquals("ESTABLISHED", chrome.state());

        PortEntry java = find(entries, PortEntry.Protocol.TCP, "*", 8080);
        assertEquals("java", java.processName());
        assertEquals("LISTEN", java.state());
        assertTrue(java.isListening());

        PortEntry cups = find(entries, PortEntry.Protocol.TCP, "::1", 631);
        assertEquals("[::1]:631", cups.localEndpoint());

        PortEntry mdns = find(entries, PortEntry.Protocol.UDP, "*", 5353);
        assertEquals("mDNSResponder", mdns.processName());
        assertEquals("", mdns.state());
        assertTrue(mdns.isListening());

        PortEntry wechat = find(entries, PortEntry.Protocol.UDP, "fe80:4::aede:48ff:fe00:1122", 5353);
        assertEquals("微信", wechat.processName());
    }

    @Test
    void parsesLinuxLsofIncludingSharedSocketsAndMappedIpv6() throws IOException {
        List<PortEntry> entries = PortTableParser.parseLsof(fixture("lsof-linux.txt"));

        List<Long> nginxPids = entries.stream().filter(e -> e.localPort() == 80).map(PortEntry::pid)
                .collect(Collectors.toList());
        assertEquals(List.of(1000L, 1001L), nginxPids);
        assertEquals("systemd-r", find(entries, PortEntry.Protocol.UDP, "127.0.0.53", 53).processName());

        PortEntry mapped = entries.stream().filter(e -> "ESTABLISHED".equals(e.state())).findFirst().orElseThrow();
        assertEquals("::ffff:10.0.0.5", mapped.localAddress());
        assertEquals("::ffff:10.0.0.9", mapped.remoteAddress());
        assertEquals(50112, mapped.remotePort());
        assertEquals(1, entries.stream().filter(e -> "CLOSE_WAIT".equals(e.state())).count());
    }

    @Test
    void unescapesLsofCommandNames() {
        assertEquals("Google Chrome", PortTableParser.unescapeLsof("Google\\x20Chrome"));
        assertEquals("a\tb", PortTableParser.unescapeLsof("a\\tb"));
        assertEquals("back\\slash", PortTableParser.unescapeLsof("back\\\\slash"));
        assertEquals("trailing\\", PortTableParser.unescapeLsof("trailing\\"));
        assertEquals("plain", PortTableParser.unescapeLsof("plain"));
    }

    // ==========================================
    // ss
    // ==========================================

    @Test
    void parsesSsWithMultipleUsersPerSocket() throws IOException {
        List<PortEntry> entries = PortTableParser.parseSs(fixture("ss-root.txt"), null);

        PortEntry resolver = find(entries, PortEntry.Protocol.UDP, "127.0.0.53%lo", 53);
        assertEquals("", resolver.state());
        assertEquals("systemd-resolve", resolver.processName());
        assertEquals(612, resolver.pid());
        assertTrue(resolver.isListening());

        PortEntry dhcp6 = find(entries, PortEntry.Protocol.UDP, "fe80::a00:27ff:fe4e:66a1%enp0s3", 546);
        assertEquals("NetworkManager", dhcp6.processName());

        // nginx master + worker 共享同一监听套接字：每个 PID 一行。
        List<Long> nginx = entries.stream().filter(e -> e.localPort() == 80 && e.localAddress().equals("0.0.0.0"))
                .map(PortEntry::pid).collect(Collectors.toList());
        assertEquals(List.of(1001L, 1000L), nginx);

        // 同一个 PID 的多个 fd 只算一次。
        assertEquals(1, entries.stream().filter(e -> e.localPort() == 8080 && e.pid() == 1234).count());
        assertEquals("*", find(entries, PortEntry.Protocol.TCP, "*", 8080).localAddress());

        PortEntry ssh = entries.stream().filter(e -> "ESTABLISHED".equals(e.state())).findFirst().orElseThrow();
        assertEquals("10.0.0.1", ssh.remoteAddress());
        assertEquals(51234, ssh.remotePort());

        PortEntry timeWait = entries.stream().filter(e -> "TIME_WAIT".equals(e.state())).findFirst().orElseThrow();
        assertEquals(PortEntry.NO_PID, timeWait.pid());

        PortEntry odd = find(entries, PortEntry.Protocol.TCP, "::ffff:127.0.0.1", 9000);
        assertEquals("my \"odd\" app", odd.processName());
    }

    @Test
    void parsesSsWithoutUsersWhenNotRoot() throws IOException {
        List<PortEntry> entries = PortTableParser.parseSs(fixture("ss-nonroot.txt"), null);

        assertEquals(5, entries.size());
        assertEquals(4, entries.stream().filter(e -> !e.hasPid()).count());
        assertEquals(1234, find(entries, PortEntry.Protocol.TCP, "*", 8080).pid());
        assertEquals("LISTEN", find(entries, PortEntry.Protocol.TCP, "::", 22).state());
    }

    @Test
    void parsesTcpOnlySsWithoutNetidColumn() throws IOException {
        List<PortEntry> entries = PortTableParser.parseSs(fixture("ss-tcp-only.txt"), PortEntry.Protocol.TCP);

        assertEquals(4, entries.size());
        assertTrue(entries.stream().allMatch(e -> e.protocol() == PortEntry.Protocol.TCP));
        assertEquals("CLOSE_WAIT", entries.get(2).state());
        assertEquals("SYN_RECEIVED", entries.get(3).state());
        assertEquals(900, entries.get(0).pid());
        // 没有默认协议时这些行无法判定，一律跳过而不是猜。
        assertTrue(PortTableParser.parseSs(fixture("ss-tcp-only.txt"), null).isEmpty());
    }

    // ==========================================
    // Linux netstat
    // ==========================================

    @Test
    void parsesLinuxNetstatWithMissingAndSpacedProgramNames() throws IOException {
        List<PortEntry> entries = PortTableParser.parseLinuxNetstat(fixture("netstat-linux.txt"));

        assertEquals(8, entries.size());
        PortEntry sshd = find(entries, PortEntry.Protocol.TCP, "0.0.0.0", 22);
        assertEquals(900, sshd.pid());
        assertEquals("sshd", sshd.processName());
        assertEquals("LISTEN", sshd.state());

        PortEntry postgres = find(entries, PortEntry.Protocol.TCP, "127.0.0.1", 5432);
        assertFalse(postgres.hasPid());
        assertEquals("LISTEN", postgres.state());

        assertEquals("sshd: alice [p", find(entries, PortEntry.Protocol.TCP, "10.0.0.5", 22).processName());
        assertEquals("nginx: master", find(entries, PortEntry.Protocol.TCP, "::", 80).processName());
        assertEquals(631, find(entries, PortEntry.Protocol.TCP, "::1", 631).pid());

        PortEntry dhcp = find(entries, PortEntry.Protocol.UDP, "0.0.0.0", 68);
        assertEquals("", dhcp.state());
        assertEquals("dhclient", dhcp.processName());
        assertTrue(dhcp.isListening());

        assertFalse(find(entries, PortEntry.Protocol.UDP, "::", 546).hasPid());
        PortEntry connectedUdp = find(entries, PortEntry.Protocol.UDP, "10.0.0.5", 40000);
        assertEquals("Web Content", connectedUdp.processName());
        assertFalse(connectedUdp.isListening());
    }

    // ==========================================
    // 基础
    // ==========================================

    @Test
    void parsesEndpointForms() {
        assertEquals(new PortTableParser.Endpoint("*", PortEntry.NO_PORT), PortTableParser.parseEndpoint("*:*"));
        assertEquals(new PortTableParser.Endpoint("::", 22), PortTableParser.parseEndpoint(":::22"));
        assertEquals(new PortTableParser.Endpoint("::", PortEntry.NO_PORT), PortTableParser.parseEndpoint(":::*"));
        assertEquals(new PortTableParser.Endpoint("fe80::1%12", 5353),
                PortTableParser.parseEndpoint("[fe80::1%12]:5353"));
        assertEquals(new PortTableParser.Endpoint("fe80::1%eth0", 546),
                PortTableParser.parseEndpoint("[fe80::1]%eth0:546"));
        assertNull(PortTableParser.parseEndpoint("no-port"));
        assertNull(PortTableParser.parseEndpoint("1.2.3.4:http"));
        assertNull(PortTableParser.parseEndpoint("1.2.3.4:70000"));
        assertNull(PortTableParser.parseEndpoint("[::1"));
    }

    @Test
    void normalizesStates() {
        assertEquals("LISTEN", PortTableParser.normalizeState("LISTENING"));
        assertEquals("LISTEN", PortTableParser.normalizeState("listen"));
        assertEquals("ESTABLISHED", PortTableParser.normalizeState("ESTAB"));
        assertEquals("TIME_WAIT", PortTableParser.normalizeState("TIME-WAIT"));
        assertEquals("FIN_WAIT_2", PortTableParser.normalizeState("FIN-WAIT-2"));
        assertEquals("", PortTableParser.normalizeState("UNCONN"));
        assertEquals("BOUND", PortTableParser.normalizeState("BOUND"));
        assertEquals("", PortTableParser.normalizeState(null));
    }

    @Test
    void parsesCommandLineOutputs() {
        assertEquals("\"C:\\Program Files\\Java\\bin\\java.exe\" -jar app.jar",
                PortTableParser.parseCommandLine("CommandLine                                    \r\n"
                        + "\"C:\\Program Files\\Java\\bin\\java.exe\" -jar app.jar     \r\n\r\n"));
        assertEquals("/usr/bin/python3 -m http.server 8000",
                PortTableParser.parseCommandLine("/usr/bin/python3 -m http.server 8000\n"));
        assertEquals("", PortTableParser.parseCommandLine("\n\n"));
        assertEquals("java -cp \"a b.jar\" Main",
                PortTableParser.parseNulSeparated("java\u0000-cp\u0000a b.jar\u0000Main\u0000"));
    }

    @Test
    void splitsCsvWithEmbeddedQuotesAndCommas() {
        assertEquals(List.of("a", "b,c", "d\"e", ""), PortTableParser.parseCsvLine("\"a\",\"b,c\",\"d\"\"e\","));
    }

    @Test
    void entryIdentityAndSearchText() {
        PortEntry entry = new PortEntry(PortEntry.Protocol.TCP, "::1", 8080, "::", PortEntry.NO_PORT,
                "LISTEN", 42, "Java.exe", "");
        assertEquals("[::1]:8080", entry.localEndpoint());
        assertEquals("[::]:*", entry.remoteEndpoint());
        assertTrue(entry.searchText().contains("java.exe"));
        assertTrue(entry.searchText().contains("42"));
        assertEquals(entry.identity(), entry.withProcessName("other").identity());
    }
}
