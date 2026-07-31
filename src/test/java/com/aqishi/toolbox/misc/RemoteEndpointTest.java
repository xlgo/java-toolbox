package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.misc.ssh.model.RemoteEndpoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteEndpointTest {

    @Test
    void parsesHostPortAndDefaultPort() {
        RemoteEndpoint explicit = RemoteEndpoint.parse("broker.internal:9092", 2181);
        assertEquals("broker.internal", explicit.getHost());
        assertEquals(9092, explicit.getPort());

        RemoteEndpoint implicit = RemoteEndpoint.parse("127.0.0.1", 2181);
        assertEquals("127.0.0.1:2181", implicit.format());

        RemoteEndpoint kafka = RemoteEndpoint.parse("sporttech.ddns.net", 9093);
        assertEquals("sporttech.ddns.net:9093", kafka.format());
    }

    @Test
    void parsesBracketedAndUnbracketedIpv6() {
        assertEquals("2001:db8::1", RemoteEndpoint.parse("[2001:db8::1]:9092", 2181).getHost());
        assertEquals("[2001:db8::1]:2181", RemoteEndpoint.parse("2001:db8::1", 2181).format());
    }

    @Test
    void parsesCommaSeparatedValuesAndRejectsInvalidPort() {
        List<RemoteEndpoint> endpoints = RemoteEndpoint.parseList("one:1, two:2", 2181);
        assertEquals(2, endpoints.size());
        assertThrows(IllegalArgumentException.class, () -> RemoteEndpoint.parse("one:not-a-port", 2181));
    }
}
