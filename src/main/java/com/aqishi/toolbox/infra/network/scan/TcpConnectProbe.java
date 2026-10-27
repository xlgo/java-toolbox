package com.aqishi.toolbox.infra.network.scan;

import com.aqishi.toolbox.feature.network.domain.ProbeOutcome;
import com.aqishi.toolbox.feature.network.domain.ProbeOutcome.State;
import com.aqishi.toolbox.feature.network.domain.ScanTargetParser.Target;

import java.io.IOException;
import java.net.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Only TCP connect; no banners, payloads or ICMP prerequisite. */
public class TcpConnectProbe implements AutoCloseable {
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ProbeOutcome probe(Target target, int port, int timeout) {
        long started = System.nanoTime();
        State state = State.CANCELLED;
        String detail = "";
        Socket socket = createSocket();
        sockets.add(socket);
        try (socket) {
            if (!closed.get() && !Thread.currentThread().isInterrupted()) {
                socket.connect(new InetSocketAddress(target.ip(), port), timeout);
                state = State.OPEN;
            }
        } catch (SocketTimeoutException error) {
            state = State.TIMEOUT;
            detail = describe(error);
        } catch (NoRouteToHostException error) {
            state = State.UNREACHABLE;
            detail = describe(error);
        } catch (ConnectException error) {
            state = State.CONNECT_FAILED;
            detail = describe(error);
        } catch (Exception error) {
            state = closed.get() ? State.CANCELLED : State.ERROR;
            detail = describe(error);
        } finally {
            sockets.remove(socket);
        }
        if (closed.get()) state = State.CANCELLED;
        return new ProbeOutcome(
                target.source(),
                target.ip(),
                port,
                state,
                (System.nanoTime() - started) / 1000,
                detail,
                System.currentTimeMillis());
    }

    private static String describe(Exception error) {
        String detail = error.getClass().getSimpleName() + ": " + error.getMessage();
        return detail.substring(0, Math.min(1000, detail.length()));
    }

    protected Socket createSocket() {
        return new Socket();
    }

    public int activeSockets() {
        return sockets.size();
    }

    @Override
    public void close() {
        closed.set(true);
        for (Socket socket : sockets)
            try {
                socket.close();
            } catch (IOException ignored) {
            }
    }
}
