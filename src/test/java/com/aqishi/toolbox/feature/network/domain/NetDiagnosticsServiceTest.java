package com.aqishi.toolbox.feature.network.domain;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 诊断服务的测试全部打在本机起的 HTTP 服务上，不依赖外网——
 * 否则这套断言在离线机器和受限 CI 上会变成随机失败。
 */
class NetDiagnosticsServiceTest {

    private static final byte[] PAYLOAD = "hello".getBytes(StandardCharsets.UTF_8);

    private NetDiagnosticsService service;
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        service = new NetDiagnosticsService();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            exchange.getResponseHeaders().add("X-Test", "yes");
            exchange.sendResponseHeaders(200, PAYLOAD.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(PAYLOAD);
            }
        });
        server.createContext("/from", exchange -> {
            exchange.getResponseHeaders().add("Location", "/ok");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void measuresPhasesForPlainHttp() {
        NetDiagnosticsService.HttpResult result = service.probeHttp(baseUrl + "/ok", "GET", 5000, 0);

        assertTrue(result.isSuccess(), String.valueOf(result.error()));
        assertEquals(200, result.statusCode());
        assertEquals(PAYLOAD.length, result.contentLength());
        assertEquals("127.0.0.1", result.remoteAddress());
        assertTrue(result.dnsMs() >= 0);
        assertTrue(result.tcpMs() >= 0);
        assertEquals(-1L, result.tlsMs(), "明文 HTTP 没有握手阶段，应标记为不适用");
        assertTrue(result.ttfbMs() >= 0);
        assertTrue(result.totalMs() >= 0);
        assertTrue(result.headers().containsKey("X-Test") || result.headers().containsKey("x-test"));
    }

    @Test
    void followsRedirectsWhenAllowed() {
        NetDiagnosticsService.HttpResult result = service.probeHttp(baseUrl + "/from", "GET", 5000, 5);

        assertTrue(result.isSuccess(), String.valueOf(result.error()));
        assertEquals(200, result.statusCode());
        assertTrue(result.url().endsWith("/ok"));
        assertEquals(1, result.redirects().size());
        assertEquals(302, result.redirects().get(0).statusCode());
        assertEquals("/ok", result.redirects().get(0).location());
    }

    @Test
    void stopsAtRedirectWhenBudgetIsZero() {
        NetDiagnosticsService.HttpResult result = service.probeHttp(baseUrl + "/from", "GET", 5000, 0);

        assertTrue(result.isSuccess(), String.valueOf(result.error()));
        assertEquals(302, result.statusCode());
        assertTrue(result.redirects().isEmpty());
    }

    @Test
    void reportsConnectionFailureWithoutThrowing() {
        NetDiagnosticsService.HttpResult result =
                service.probeHttp("http://127.0.0.1:1/unreachable", "GET", 1000, 0);

        assertFalse(result.isSuccess());
        assertNotNull(result.error());
    }

    @Test
    void reportsMissingInputWithStableCodes() {
        assertEquals("empty.url", service.probeHttp(" ", "GET", 1000, 0).error());
        assertEquals("empty.host", service.lookup(" ", List.of("A"), "", 1000).error());
        assertEquals("empty.host", service.reverseLookup("", "", 1000).error());
        assertEquals("empty.host", service.inspectTls(" ", 443, "", 1000).error());
    }

    /** 没写协议时按 https 补全，这样直接粘域名也能测。 */
    @Test
    void assumesHttpsWhenSchemeIsMissing() {
        NetDiagnosticsService.HttpResult result =
                service.probeHttp("127.0.0.1:1", "GET", 1000, 0);

        assertFalse(result.isSuccess());
        assertTrue(result.url().startsWith("https://"));
    }

    @Test
    void resolvesLoopbackThroughSystemResolver() {
        NetDiagnosticsService.DnsResult result =
                service.lookup("localhost", List.of("A"), "", 1000);

        assertTrue(result.isSuccess(), String.valueOf(result.error()));
        assertTrue(result.records().stream()
                .anyMatch(record -> record.value().contains("127.0.0.1")
                        || record.value().contains("0:0:0:0:0:0:0:1")));
    }

    /** 对着明文端口握手必然失败，但必须是一个可读的失败，而不是异常穿透到界面。 */
    @Test
    void tlsHandshakeAgainstPlainPortFailsCleanly() {
        NetDiagnosticsService.TlsResult result = service.inspectTls(
                "127.0.0.1", server.getAddress().getPort(), "", 2000);

        assertFalse(result.trusted());
        assertTrue(result.chain().isEmpty());
        assertNotNull(result.error() == null ? result.trustError() : result.error());
    }

    /**
     * 回归：JNDI 的 DNS 提供方对「一次问多种类型」一律返回空属性集，既不报错也不给记录，
     * 所以服务必须逐类型发问。这个 bug 的症状是 MX/TXT 永远查不到，只剩系统解析栈的 A/AAAA。
     *
     * <p>需要能真正解析的环境；单类型查询都拿不到结果时说明本机没有可用的 DNS，直接跳过。</p>
     */
    @Test
    void asksOneRecordTypePerQuery() {
        NetDiagnosticsService.DnsResult single =
                service.lookup("example.com", List.of("MX"), "", 3000);
        org.junit.jupiter.api.Assumptions.assumeTrue(
                single.isSuccess() && hasType(single, "MX"),
                "当前环境没有可用的 DNS 解析器，跳过");

        NetDiagnosticsService.DnsResult combined =
                service.lookup("example.com", List.of("A", "MX"), "", 3000);

        assertTrue(hasType(combined, "A"), "多类型查询丢了 A 记录");
        assertTrue(hasType(combined, "MX"), "多类型查询丢了 MX 记录，说明又退回了单次多类型请求");
    }

    private static boolean hasType(NetDiagnosticsService.DnsResult result, String type) {
        return result.records().stream().anyMatch(record -> type.equals(record.type()));
    }

    @Test
    void exposesRecordTypesAndResolverPresets() {
        assertTrue(NetDiagnosticsService.RECORD_TYPES.containsAll(
                NetDiagnosticsService.DEFAULT_RECORD_TYPES));
        assertTrue(NetDiagnosticsService.commonResolvers().contains(""),
                "留空项代表系统解析器，必须保留");
    }
}
