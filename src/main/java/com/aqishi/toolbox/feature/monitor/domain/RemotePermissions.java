package com.aqishi.toolbox.feature.monitor.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable set of rights the host granted to a controller.
 *
 * <p>{@link Permission#VIEW} is the base of every grant: a set without it
 * means "denied". File transfer and terminal are separate, explicit grants
 * because each is strictly more powerful than watching the screen.</p>
 */
public final class RemotePermissions {

    public enum Permission {
        /** Receive screen frames. */
        VIEW,
        /** Inject mouse/keyboard input and draw annotations on the host screen. */
        CONTROL,
        /** Write files into the host's Downloads directory. */
        FILES,
        /** Run a shell on the host. */
        TERMINAL;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final RemotePermissions NONE = new RemotePermissions(EnumSet.noneOf(Permission.class));

    private final Set<Permission> granted;

    private RemotePermissions(EnumSet<Permission> granted) {
        this.granted = Collections.unmodifiableSet(granted);
    }

    public static RemotePermissions none() {
        return NONE;
    }

    public static RemotePermissions of(Permission... permissions) {
        EnumSet<Permission> set = EnumSet.noneOf(Permission.class);
        Collections.addAll(set, permissions);
        return new RemotePermissions(set);
    }

    public static RemotePermissions of(Collection<Permission> permissions) {
        EnumSet<Permission> set = EnumSet.noneOf(Permission.class);
        set.addAll(permissions);
        return new RemotePermissions(set);
    }

    /** Parses wire names, ignoring unknown entries (forward compatible, never widening). */
    public static RemotePermissions fromWire(Collection<?> names) {
        EnumSet<Permission> set = EnumSet.noneOf(Permission.class);
        if (names != null) {
            for (Object name : names) {
                for (Permission permission : Permission.values()) {
                    if (permission.wireName().equals(String.valueOf(name))) set.add(permission);
                }
            }
        }
        return new RemotePermissions(set);
    }

    public List<String> toWire() {
        List<String> names = new ArrayList<>();
        for (Permission permission : granted) names.add(permission.wireName());
        return names;
    }

    public boolean has(Permission permission) {
        return granted.contains(permission);
    }

    /** True for any real grant (it contains VIEW); false means denied. */
    public boolean isGranted() {
        return granted.contains(Permission.VIEW);
    }

    public Set<Permission> asSet() {
        return granted;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RemotePermissions && ((RemotePermissions) other).granted.equals(granted);
    }

    @Override
    public int hashCode() {
        return granted.hashCode();
    }

    @Override
    public String toString() {
        return granted.toString();
    }
}
