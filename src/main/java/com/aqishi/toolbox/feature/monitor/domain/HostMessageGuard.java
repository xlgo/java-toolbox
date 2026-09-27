package com.aqishi.toolbox.feature.monitor.domain;

import com.aqishi.toolbox.feature.monitor.domain.RemotePermissions.Permission;

/**
 * Maps each inbound controller message type to the permission it needs on the host.
 *
 * <p>Default deny: a type that is not listed here is never delivered to a
 * host handler, so adding a new message type cannot silently bypass consent.</p>
 */
public final class HostMessageGuard {

    private HostMessageGuard() {
    }

    /** Permission required for an inbound message, or null if the host never accepts it. */
    public static Permission requiredPermission(byte type) {
        switch (type) {
            case DesktopMessage.TYPE_CONTROL_EVENT:
            case DesktopMessage.TYPE_DRAWING:
                return Permission.CONTROL;
            case DesktopMessage.TYPE_FILE_TRANSFER:
                return Permission.FILES;
            case DesktopMessage.TYPE_CMD_REQUEST:
                return Permission.TERMINAL;
            case DesktopMessage.TYPE_HEARTBEAT:
                return Permission.VIEW;
            default:
                return null;
        }
    }

    public static boolean allows(byte type, RemotePermissions granted) {
        Permission required = requiredPermission(type);
        return required != null && granted != null && granted.isGranted() && granted.has(required);
    }
}
