package com.aqishi.toolbox.feature.monitor.domain;

import com.aqishi.toolbox.feature.monitor.domain.RemotePermissions.Permission;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostSessionAccessTest {

    private static final byte[] TYPES = {
            DesktopMessage.TYPE_SCREEN_FRAME, DesktopMessage.TYPE_CONTROL_EVENT, DesktopMessage.TYPE_CMD_REQUEST,
            DesktopMessage.TYPE_CMD_RESPONSE, DesktopMessage.TYPE_FILE_TRANSFER, DesktopMessage.TYPE_DRAWING,
            DesktopMessage.TYPE_HEARTBEAT, DesktopMessage.TYPE_P2P_SIGNAL, DesktopMessage.TYPE_SESSION_CONTROL,
            DesktopMessage.TYPE_SECURE_HANDSHAKE, DesktopMessage.TYPE_SECURE_RECORD, (byte) 0x7F};

    private final List<Byte> handled = new CopyOnWriteArrayList<>();
    private final HostSessionAccess access = new HostSessionAccess(message -> handled.add(message.getType()));

    private static HostConsent.Request request() {
        return new HostConsent.Request("alice", "RD-C", "10.0.0.2:5000", "123 456", "TCP", false);
    }

    private static HostConsent.Strategy answering(RemotePermissions answer) {
        return request -> CompletableFuture.completedFuture(answer);
    }

    private void sendAll() {
        for (byte type : TYPES) access.accept(new DesktopMessage(type, new byte[0]));
    }

    @Test
    void nothingIsHandledBeforeConsent() {
        HostConsent.Strategy pending = request -> new CompletableFuture<>();
        access.requestConsent(pending, request(), 60_000);
        sendAll();
        assertTrue(handled.isEmpty());
        assertEquals(TYPES.length, access.droppedCount());
    }

    @Test
    void viewOnlyGrantDropsControlFileAndTerminal() throws Exception {
        HostSessionAccess.Outcome outcome = access.requestConsent(
                answering(RemotePermissions.of(Permission.VIEW)), request(), 60_000).get(1, TimeUnit.SECONDS);
        assertEquals(HostSessionAccess.Outcome.GRANTED, outcome);
        sendAll();
        assertEquals(List.of(DesktopMessage.TYPE_HEARTBEAT), handled);
    }

    @Test
    void controlGrantStillRequiresSeparateFileAndTerminalGrants() throws Exception {
        access.requestConsent(answering(RemotePermissions.of(Permission.VIEW, Permission.CONTROL)),
                request(), 60_000).get(1, TimeUnit.SECONDS);
        sendAll();
        assertEquals(List.of(DesktopMessage.TYPE_CONTROL_EVENT, DesktopMessage.TYPE_DRAWING,
                DesktopMessage.TYPE_HEARTBEAT), handled);
    }

    @Test
    void fullGrantAllowsEveryHostMessageButNeverTransportOrHostToControllerTypes() throws Exception {
        access.requestConsent(answering(RemotePermissions.of(Permission.values())), request(), 60_000)
                .get(1, TimeUnit.SECONDS);
        sendAll();
        assertEquals(List.of(DesktopMessage.TYPE_CONTROL_EVENT, DesktopMessage.TYPE_CMD_REQUEST,
                DesktopMessage.TYPE_FILE_TRANSFER, DesktopMessage.TYPE_DRAWING, DesktopMessage.TYPE_HEARTBEAT),
                handled);
    }

    @Test
    void grantWithoutViewCountsAsDenial() throws Exception {
        HostSessionAccess.Outcome outcome = access.requestConsent(
                answering(RemotePermissions.of(Permission.CONTROL, Permission.TERMINAL)), request(), 60_000)
                .get(1, TimeUnit.SECONDS);
        assertEquals(HostSessionAccess.Outcome.DENIED, outcome);
        sendAll();
        assertTrue(handled.isEmpty());
    }

    @Test
    void denyKeepsEverythingBlocked() throws Exception {
        assertEquals(HostSessionAccess.Outcome.DENIED,
                access.requestConsent(answering(RemotePermissions.none()), request(), 60_000)
                        .get(1, TimeUnit.SECONDS));
        sendAll();
        assertTrue(handled.isEmpty());
        assertEquals(HostSessionAccess.State.DENIED, access.state());
    }

    @Test
    void unansweredPromptIsDeniedAfterTimeoutAndWithdrawn() throws Exception {
        AtomicReference<CompletableFuture<RemotePermissions>> prompt = new AtomicReference<>();
        HostConsent.Strategy silent = request -> {
            CompletableFuture<RemotePermissions> future = new CompletableFuture<>();
            prompt.set(future);
            return future;
        };
        HostSessionAccess.Outcome outcome = access.requestConsent(silent, request(), 100)
                .get(5, TimeUnit.SECONDS);
        assertEquals(HostSessionAccess.Outcome.TIMED_OUT, outcome);
        assertTrue(prompt.get().isDone(), "the dialog must be told to close");
        prompt.get().complete(RemotePermissions.of(Permission.values()));
        sendAll();
        assertTrue(handled.isEmpty(), "a late click must not grant anything");
    }

    @Test
    void closingRevokesAGrantAndWithdrawsAPendingPrompt() throws Exception {
        access.requestConsent(answering(RemotePermissions.of(Permission.values())), request(), 60_000)
                .get(1, TimeUnit.SECONDS);
        access.close();
        sendAll();
        assertTrue(handled.isEmpty());

        HostSessionAccess other = new HostSessionAccess(message -> handled.add(message.getType()));
        CompletableFuture<RemotePermissions> prompt = new CompletableFuture<>();
        CompletableFuture<HostSessionAccess.Outcome> outcome = other.requestConsent(r -> prompt, request(), 60_000);
        other.close();
        assertTrue(prompt.isDone());
        assertEquals(HostSessionAccess.Outcome.CLOSED, outcome.get(1, TimeUnit.SECONDS));
    }

    @Test
    void consentCanOnlyBeRequestedOnce() throws Exception {
        access.requestConsent(answering(RemotePermissions.of(Permission.VIEW)), request(), 60_000)
                .get(1, TimeUnit.SECONDS);
        assertEquals(HostSessionAccess.Outcome.DENIED,
                access.requestConsent(answering(RemotePermissions.of(Permission.values())), request(), 60_000)
                        .get(1, TimeUnit.SECONDS));
        assertFalse(access.granted().has(Permission.TERMINAL));
    }

    @Test
    void sessionControlMessagesRoundTrip() {
        RemotePermissions granted = RemotePermissions.of(Permission.VIEW, Permission.FILES);
        SessionControlMessages.Control grant = SessionControlMessages.parse(SessionControlMessages.grant(granted));
        assertNotNull(grant);
        assertTrue(grant.isGranted());
        assertEquals(granted, grant.permissions());
        SessionControlMessages.Control deny = SessionControlMessages.parse(
                SessionControlMessages.deny(SessionControlMessages.REASON_TIMEOUT));
        assertNotNull(deny);
        assertFalse(deny.isGranted());
        assertEquals(SessionControlMessages.REASON_TIMEOUT, deny.reason());
        assertNull(SessionControlMessages.parse(new DesktopMessage(DesktopMessage.TYPE_SESSION_CONTROL,
                "not json".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
}
