package com.aqishi.toolbox.infra;

/**
 * UI-facing boundary for a component that owns one or more infrastructure
 * resources.
 *
 * <p>The desktop shell invokes this method during shutdown. Implementations
 * must make it safe to call repeatedly and must not show UI or throw checked
 * exceptions while releasing sockets, tunnels, timers, or client handles.</p>
 */
public interface ManagedResourceOwner {

    /**
     * Releases all resources owned by this component.
     */
    void closeResources();
}
