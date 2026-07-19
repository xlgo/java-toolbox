package com.aqishi.toolbox.monitor;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class IceProbeCodecTest {

    @Test
    void createsAndAuthenticatesBindingRequestAndSuccess() {
        byte[] transactionId = IceProbeCodec.newTransactionId();
        byte[] request = IceProbeCodec.createBindingRequest(transactionId);

        IceProbeCodec.ParsedMessage parsedRequest =
                IceProbeCodec.parse(request, 0, request.length);
        assertNotNull(parsedRequest);
        assertEquals(IceProbeCodec.BINDING_REQUEST, parsedRequest.getType());
        assertArrayEquals(transactionId, parsedRequest.getTransactionId());

        byte[] response = IceProbeCodec.createBindingSuccess(
                transactionId, new InetSocketAddress("203.0.113.9", 45678));
        IceProbeCodec.ParsedMessage parsedResponse =
                IceProbeCodec.parse(response, 0, response.length);
        assertNotNull(parsedResponse);
        assertEquals(IceProbeCodec.BINDING_SUCCESS, parsedResponse.getType());
        assertArrayEquals(transactionId, parsedResponse.getTransactionId());
    }

    @Test
    void rejectsTamperedConnectivityCheck() {
        byte[] request = IceProbeCodec.createBindingRequest(
                IceProbeCodec.newTransactionId());
        request[24] ^= 0x01;

        assertNull(IceProbeCodec.parse(request, 0, request.length));
    }
}
