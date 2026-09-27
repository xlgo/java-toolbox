package com.aqishi.toolbox.feature.monitor.domain;

import com.aqishi.toolbox.util.Json;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encrypted host-to-controller session control ({@link DesktopMessage#TYPE_SESSION_CONTROL}).
 *
 * <pre>
 *  {"action":"grant","permissions":["view","control",...]}
 *  {"action":"deny","reason":"denied"|"timeout"|"busy"}
 * </pre>
 * The grant only drives the controller's UI; the host enforces permissions on
 * its own side ({@link HostSessionAccess}).
 */
public final class SessionControlMessages {

    public static final String REASON_DENIED = "denied";
    public static final String REASON_TIMEOUT = "timeout";

    private static final ObjectMapper MAPPER = Json.mapper();

    /** Parsed control message. {@code permissions} is none() for a deny. */
    public static final class Control {
        private final boolean granted;
        private final RemotePermissions permissions;
        private final String reason;

        private Control(boolean granted, RemotePermissions permissions, String reason) {
            this.granted = granted;
            this.permissions = permissions;
            this.reason = reason;
        }

        public boolean isGranted() {
            return granted;
        }

        public RemotePermissions permissions() {
            return permissions;
        }

        public String reason() {
            return reason;
        }
    }

    private SessionControlMessages() {
    }

    public static DesktopMessage grant(RemotePermissions permissions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "grant");
        body.put("permissions", permissions.toWire());
        return encode(body);
    }

    public static DesktopMessage deny(String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "deny");
        body.put("reason", reason == null ? REASON_DENIED : reason);
        return encode(body);
    }

    /** Returns null if the message is not a well-formed session control message. */
    public static Control parse(DesktopMessage message) {
        if (message == null || message.getType() != DesktopMessage.TYPE_SESSION_CONTROL) return null;
        try {
            Map<?, ?> body = MAPPER.readValue(new String(message.getPayload(), StandardCharsets.UTF_8), Map.class);
            Object action = body.get("action");
            if ("grant".equals(action)) {
                Object names = body.get("permissions");
                RemotePermissions permissions = RemotePermissions.fromWire(
                        names instanceof List ? (List<?>) names : null);
                return permissions.isGranted()
                        ? new Control(true, permissions, null)
                        : new Control(false, RemotePermissions.none(), REASON_DENIED);
            }
            if ("deny".equals(action)) {
                Object reason = body.get("reason");
                return new Control(false, RemotePermissions.none(),
                        reason == null ? REASON_DENIED : String.valueOf(reason));
            }
        } catch (Exception ignored) {
            // Malformed control messages are ignored; the host keeps enforcing.
        }
        return null;
    }

    private static DesktopMessage encode(Map<String, Object> body) {
        try {
            return new DesktopMessage(DesktopMessage.TYPE_SESSION_CONTROL,
                    MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("cannot encode session control message", e);
        }
    }
}
