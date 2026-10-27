package com.aqishi.toolbox.feature.network.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class ScanExpressionTest {
    @Test
    void rangesCidrsAndListsProduceThePromisedCounts() {
        var targets = ScanTargetParser.parse("192.168.1.10-20", false);
        var ports = PortExpressionParser.combine(List.of(), "22,80,443,8080-8082", "");
        assertEquals(66, targets.numericCount() * ports.size());
        var subnet = ScanTargetParser.parse("192.168.1.23/24", false);
        var thirteen = PortExpressionParser.parse("22,80,443,2379,2380,8080-8085,9092,9093");
        assertEquals(254, subnet.numericCount());
        assertEquals(13, thirteen.size());
        assertEquals(3302, subnet.numericCount() * thirteen.size());
        assertEquals("192.168.1.1", subnet.expand(300).get(0).ip());
    }

    @Test
    void overlappingRangesAndExclusionsAreDeduplicatedBeforeExpansion() {
        var target =
                ScanTargetParser.subtract(
                        ScanTargetParser.parse(
                                "192.168.1.10-20,192.168.1.15-25,192.168.1.10", false),
                        ScanTargetParser.parse("192.168.1.12-15", true));
        assertEquals(12, target.numericCount());
        assertEquals(
                12, target.expand(12).stream().map(ScanTargetParser.Target::ip).distinct().count());
        assertEquals(
                List.of(22, 443, 8080),
                PortExpressionParser.combine(List.of("22,80,443"), "22;8080，8080；443\n80", "80"));
    }

    @Test
    void cidrEdgesAndCrossSubnetRangesHaveExplicitSemantics() {
        assertEquals(256, ScanTargetParser.parse("10.0.0.23/24", true).numericCount());
        assertEquals(2, ScanTargetParser.parse("10.0.0.0/31", false).numericCount());
        assertEquals(1, ScanTargetParser.parse("10.0.0.1/32", false).numericCount());
        assertEquals(
                27, ScanTargetParser.parse("192.168.1.250-192.168.2.20", false).numericCount());
        assertEquals(256, ScanTargetParser.parse("10.0.0.0-255", false).numericCount());
    }

    @Test
    void singleIpv6AndHyphenatedHostnamesDoNotBecomeIpv4Ranges() {
        var target = ScanTargetParser.parse("::1,[::1],server-a.example,SERVER-A.example", false);
        assertEquals(1, target.numericCount());
        assertEquals(List.of("server-a.example"), target.names());
        assertTrue(target.expand(1).get(0).ip().contains(":"));
    }

    @Test
    void invalidItemsNeverProduceAPartialScan() {
        for (String port : List.of("0", "65536", "80-22", "abc", "22--23", "1-999999999999"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PortExpressionParser.parse("22," + port),
                    port);
        for (String target :
                List.of(
                        "300.1.1.1",
                        "1.2.3",
                        "10.0.0.10-1",
                        "10.0.0.1/33",
                        "10.0.0.1/abc",
                        "192.168.1.2:80",
                        "https://example.com",
                        "2001:db8::/129",
                        "a..b",
                        "01.2.3.4"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ScanTargetParser.parse(target, false),
                    target);
    }

    @Test
    void giantNetworksAreRejectedWithoutExpandingThem() {
        var full = ScanTargetParser.parse("0.0.0.0/0", true);
        assertEquals(4294967296L, full.numericCount());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ScanPlan(
                                full,
                                ScanTargetParser.parse("", true),
                                List.of(80),
                                ScanPlan.Settings.defaults()));
        var small =
                ScanTargetParser.subtract(
                        full, ScanTargetParser.parse("0.0.0.0-255.255.255.253", true));
        assertEquals(2, small.numericCount());
        assertEquals("255.255.255.254", small.expand(2).get(0).ip());
    }

    @Test
    void budgetsUseUniquePortsAndRejectProbeProducts() {
        var targets = ScanTargetParser.parse("10.0.0.0/24", false);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ScanPlan(
                                targets,
                                ScanTargetParser.parse("", true),
                                List.copyOf(PortExpressionParser.parse("1-65535")),
                                ScanPlan.Settings.defaults()));
        ScanPlan plan =
                new ScanPlan(
                        targets,
                        ScanTargetParser.parse("", true),
                        List.of(80, 80),
                        ScanPlan.Settings.defaults());
        assertEquals(List.of(80), plan.ports());
        for (String group : PortPresets.groups())
            assertFalse(PortExpressionParser.parse(PortPresets.ports(group)).isEmpty());
        assertEquals("etcd client", PortPresets.service(2379));
    }

    @Test
    void ipv6RangesCidrsAndExclusionsAreExactAndDeduplicated() {
        var included =
                ScanTargetParser.parse(
                        "2001:db8::3/126,2001:db8::1-2001:db8::5,[2001:db8::2]", false);
        var targets =
                ScanTargetParser.subtract(
                        included, ScanTargetParser.parse("2001:db8::2-2001:db8::3", true));
        assertEquals(4, targets.numericCount());
        assertEquals(
                4, targets.expand(4).stream().map(ScanTargetParser.Target::ip).distinct().count());
        assertTrue(targets.contains("2001:db8::1"));
        assertFalse(targets.contains("2001:db8::2"));
        assertTrue(targets.contains("2001:db8::5"));
        assertTrue(targets.expand(4).stream().noneMatch(target -> target.ip().contains("%0")));
        assertEquals(1, ScanTargetParser.parse("::1/128", false).numericCount());
        assertEquals(2, ScanTargetParser.parse("::/127", false).numericCount());
        assertEquals(1, ScanTargetParser.parse("::1,::1%0", false).numericCount());
        var scoped = ScanTargetParser.parse("fe80::%1/126", false);
        assertTrue(scoped.contains("fe80::1%1"));
        assertFalse(scoped.contains("fe80::1%2"));
        assertThrows(
                IllegalArgumentException.class, () -> ScanTargetParser.parse("::5-::1", false));
        assertThrows(
                IllegalArgumentException.class, () -> ScanTargetParser.parse("[[::1]-::4", false));
    }

    @Test
    void enormousIpv6NetworksAreBudgetCheckedWithoutEnumerationOrOverflow() {
        var all = ScanTargetParser.parse("::/0,server-a", false);
        assertEquals(Long.MAX_VALUE, all.numericCount());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ScanPlan(
                                all,
                                ScanTargetParser.parse("", true),
                                List.of(80),
                                ScanPlan.Settings.defaults()));
        var lastFour =
                ScanTargetParser.subtract(
                        all,
                        ScanTargetParser.parse("::-ffff:ffff:ffff:ffff:ffff:ffff:ffff:fffb", true));
        assertEquals(4, lastFour.numericCount());
        assertEquals(4, lastFour.expand(4).size());
    }

    @Test
    void trailingIpv4WildcardsAreRangesAndPortsCanBeSavedCompactly() {
        assertEquals(256, ScanTargetParser.parse("192.168.1.*", false).numericCount());
        assertEquals(65536, ScanTargetParser.parse("10.20.*.*", false).numericCount());
        assertThrows(
                IllegalArgumentException.class, () -> ScanTargetParser.parse("192.168.*.1", false));
        var ports = PortExpressionParser.parse("1-65535");
        assertEquals("1-65535", PortExpressionParser.format(ports));
        assertEquals(ports, PortExpressionParser.parse(PortExpressionParser.format(ports)));
    }
}
