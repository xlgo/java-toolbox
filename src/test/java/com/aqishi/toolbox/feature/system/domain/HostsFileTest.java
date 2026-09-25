package com.aqishi.toolbox.feature.system.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostsFileTest {

    private static final String SAMPLE = String.join("\r\n",
            "# Copyright (c) 1993-2009 Microsoft Corp.",
            "#",
            "#      102.54.94.97     rhino.acme.com          # source server",
            "",
            "127.0.0.1 localhost localhost.localdomain",
            "::1       localhost",
            "fe00::0   ip6-localnet",
            "ff02::1   ip6-allnodes",
            "# Added by Docker Desktop",
            "192.168.1.5 host.docker.internal",
            "# End of section",
            "10.0.0.8\tapi.dev  # 本地联调",
            "");

    /** 未改动时必须逐字节写回原文：注释、空行、IPv6、Docker 区段、CRLF 都不能动。 */
    @Test
    void unchangedRoundTripIsByteForByteIdentical() {
        HostsFile file = HostsFile.parse(SAMPLE);

        assertEquals(SAMPLE, file.render(file.entries()));
    }

    /** 回归：旧实现只认 IPv4 和 ::1，其他 IPv6 条目在保存后消失。 */
    @Test
    void recognisesIpv6AndDisabledEntries() {
        List<HostsFile.Entry> entries = HostsFile.parse(SAMPLE).entries();

        assertEquals(List.of("102.54.94.97", "127.0.0.1", "::1", "fe00::0", "ff02::1", "192.168.1.5", "10.0.0.8"),
                entries.stream().map(HostsFile.Entry::ip).toList());
        assertFalse(entries.get(0).enabled());
        assertEquals("source server", entries.get(0).comment());
    }

    /** 回归：别名曾被挪进注释（localhost.localdomain 成了 "# localhost.localdomain"）。 */
    @Test
    void keepsAliasesAsHosts() {
        HostsFile.Entry localhost = HostsFile.parse(SAMPLE).entries().get(1);

        assertEquals(List.of("localhost", "localhost.localdomain"), localhost.hosts());
        assertEquals("", localhost.comment());
    }

    @Test
    void editsDeletesAndAdditionsAreAppliedInPlace() {
        HostsFile file = HostsFile.parse(SAMPLE);
        List<HostsFile.Entry> edited = new ArrayList<>();
        for (HostsFile.Entry entry : file.entries()) {
            if (entry.ip().equals("192.168.1.5")) {
                continue; // 删除
            }
            if (entry.ip().equals("10.0.0.8")) {
                edited.add(HostsFile.entry(entry.id(), false, "10.0.0.9", "api.dev", "切到测试环境"));
                continue;
            }
            edited.add(entry);
        }
        edited.add(HostsFile.entry(null, true, "127.0.0.1", "new.local", ""));

        String output = file.render(edited);

        assertFalse(output.contains("192.168.1.5"));
        assertTrue(output.contains("# Added by Docker Desktop\r\n# End of section"), output);
        assertTrue(output.contains("# 10.0.0.9\tapi.dev\t# 切到测试环境\r\n127.0.0.1\tnew.local\r\n"), output);
        assertTrue(output.startsWith("# Copyright (c) 1993-2009 Microsoft Corp.\r\n"));
        assertTrue(output.endsWith("\r\n"));
    }

    @Test
    void blankRowsFromTheTableAreIgnored() {
        HostsFile file = HostsFile.parse("127.0.0.1 a\n");
        List<HostsFile.Entry> edited = new ArrayList<>(file.entries());
        edited.add(HostsFile.entry(null, true, "", "", ""));

        assertEquals("127.0.0.1 a\n", file.render(edited));
    }

    @Test
    void validatesIpAddresses() {
        assertTrue(HostsFile.isIpAddress("10.0.0.1"));
        assertTrue(HostsFile.isIpAddress("fe80::1%eth0"));
        assertFalse(HostsFile.isIpAddress("999.1.1.1"));
        assertFalse(HostsFile.isIpAddress("localhost"));
        assertFalse(HostsFile.isIpAddress("Copyright"));
    }
}
