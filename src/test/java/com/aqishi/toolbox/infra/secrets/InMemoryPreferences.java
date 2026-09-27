package com.aqishi.toolbox.infra.secrets;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;

/** Preferences node kept in memory so tests never touch the real user preferences. */
public final class InMemoryPreferences extends AbstractPreferences {
    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, InMemoryPreferences> children = new HashMap<>();
    /** When true every put fails, simulating an unwritable backing store. */
    public volatile boolean failWrites;
    public int flushCount;

    public InMemoryPreferences() {
        super(null, "");
    }

    private InMemoryPreferences(InMemoryPreferences parent, String name) {
        super(parent, name);
    }

    /** Snapshot of the raw stored key/value pairs. */
    public Map<String, String> raw() {
        synchronized (lock) {
            return new LinkedHashMap<>(values);
        }
    }

    @Override
    protected void putSpi(String key, String value) {
        if (failWrites) throw new IllegalStateException("preferences are read-only");
        values.put(key, value);
    }

    @Override
    protected String getSpi(String key) {
        return values.get(key);
    }

    @Override
    protected void removeSpi(String key) {
        values.remove(key);
    }

    @Override
    protected void removeNodeSpi() {
        values.clear();
    }

    @Override
    protected String[] keysSpi() {
        return values.keySet().toArray(new String[0]);
    }

    @Override
    protected String[] childrenNamesSpi() {
        return children.keySet().toArray(new String[0]);
    }

    @Override
    protected AbstractPreferences childSpi(String name) {
        return children.computeIfAbsent(name, n -> new InMemoryPreferences(this, n));
    }

    @Override
    protected void syncSpi() throws BackingStoreException {
    }

    @Override
    protected void flushSpi() throws BackingStoreException {
        flushCount++;
    }
}
