package com.aqishi.toolbox.feature.network.domain;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 只用 IP 字面量与 localhost，不依赖测试机的 DNS。 */
class BenchTargetHostTest {

    private static BenchTargetHost.Scope scope(String host) {
        return BenchTargetHost.of(host).scope();
    }

    @Test
    void loopbackAddresses() {
        assertEquals(BenchTargetHost.Scope.LOOPBACK, scope("127.0.0.1"));
        assertEquals(BenchTargetHost.Scope.LOOPBACK, scope("127.8.9.10"));
        assertEquals(BenchTargetHost.Scope.LOOPBACK, scope("localhost"));
        assertEquals(BenchTargetHost.Scope.LOOPBACK, scope("LOCALHOST."));
        assertEquals(BenchTargetHost.Scope.LOOPBACK, scope("::1"));
        assertEquals(BenchTargetHost.Scope.LOOPBACK, scope("[::1]"));
        assertEquals(BenchTargetHost.Scope.LOOPBACK, scope("0:0:0:0:0:0:0:1"));
    }

    @Test
    void privateRanges() {
        assertEquals(BenchTargetHost.Scope.PRIVATE, scope("10.1.2.3"));
        assertEquals(BenchTargetHost.Scope.PRIVATE, scope("172.16.0.1"));
        assertEquals(BenchTargetHost.Scope.PRIVATE, scope("172.31.255.255"));
        assertEquals(BenchTargetHost.Scope.PRIVATE, scope("192.168.1.10"));
        assertEquals(BenchTargetHost.Scope.PRIVATE, scope("fd12:3456::1"));
        assertEquals(BenchTargetHost.Scope.PRIVATE, scope("::ffff:10.0.0.1"));
    }

    @Test
    void linkLocalRanges() {
        assertEquals(BenchTargetHost.Scope.LINK_LOCAL, scope("169.254.10.20"));
        assertEquals(BenchTargetHost.Scope.LINK_LOCAL, scope("fe80::1"));
        assertEquals(BenchTargetHost.Scope.LINK_LOCAL, scope("fe80::1%eth0"));
    }

    @Test
    void publicAndReservedNeedConfirmation() {
        assertEquals(BenchTargetHost.Scope.PUBLIC, scope("8.8.8.8"));
        assertEquals(BenchTargetHost.Scope.PUBLIC, scope("172.32.0.1"));
        assertEquals(BenchTargetHost.Scope.PUBLIC, scope("172.15.0.1"));
        assertEquals(BenchTargetHost.Scope.PUBLIC, scope("2001:4860:4860::8888"));
        assertEquals(BenchTargetHost.Scope.RESERVED, scope("100.64.0.1"));
        assertEquals(BenchTargetHost.Scope.RESERVED, scope("224.0.0.1"));
        assertTrue(BenchTargetHost.of("8.8.8.8").requiresAuthorizationWarning());
        assertTrue(BenchTargetHost.of("100.64.0.1").requiresAuthorizationWarning());
        assertTrue(BenchTargetHost.of("").requiresAuthorizationWarning());
        assertFalse(BenchTargetHost.of("192.168.0.1").requiresAuthorizationWarning());
        assertFalse(BenchTargetHost.of("fe80::1").requiresAuthorizationWarning());
    }

    @Test
    void malformedLiteralsAreNotTrusted() {
        assertEquals(BenchTargetHost.Scope.PUBLIC, scope("1:2:3:zz::1"));
        assertEquals(BenchTargetHost.Scope.PUBLIC, scope("fe80:::::::1"));
    }

    @Test
    void mixedResolutionTakesTheLeastTrustedAddress() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        InetAddress lan = InetAddress.getByName("192.168.0.2");
        InetAddress internet = InetAddress.getByName("93.184.216.34");

        assertEquals(BenchTargetHost.Scope.PRIVATE,
                BenchTargetHost.combine(new InetAddress[]{loopback, lan}));
        assertEquals(BenchTargetHost.Scope.LOOPBACK,
                BenchTargetHost.combine(new InetAddress[]{loopback, loopback}));
        assertEquals(BenchTargetHost.Scope.PUBLIC,
                BenchTargetHost.combine(new InetAddress[]{loopback, internet}));
    }

    @Test
    void classifiesUriHost() {
        assertEquals(BenchTargetHost.Scope.LOOPBACK,
                BenchTargetHost.of(URI.create("http://[::1]:8080/x")).scope());
        assertEquals(BenchTargetHost.Scope.PRIVATE,
                BenchTargetHost.of(URI.create("https://10.0.0.8/api")).scope());
    }
}
