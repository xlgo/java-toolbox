package com.aqishi.toolbox.feature.network.application;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SocketLifecycleTest {
    @Test
    void closeBeforeOpenCannotAllocatePortsOrThreads() {
        for (SocketSession session : List.of(
                new TcpServerSession(new TcpServerSession.Options().bindHost("127.0.0.1").port(0), e -> {}),
                new UdpSession(new UdpSession.Options().bindHost("127.0.0.1").bindPort(0), e -> {}))) {
            try {
                session.close();
                assertThrows(IOException.class, session::open, session.kind().toString());
                assertNull(session.localAddress());
                assertFalse(session.isOpen());
            } finally { session.close(); }
        }
    }
}
