package com.aqishi.toolbox.infra.config;

import com.aqishi.toolbox.infra.InfrastructureException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.prefs.*;
import static org.junit.jupiter.api.Assertions.*;

class JsonPreferencesFailureTest {
    private final FailingPreferences prefs = new FailingPreferences();
    private final JsonPreferencesStore<String> store = new JsonPreferencesStore<>(prefs, "items", new TypeReference<LinkedHashMap<String, String>>() {});

    @ParameterizedTest
    @ValueSource(strings = {"@chunked:0:0", "@chunked:0:-1", "@chunked:0:1:extra", "@chunked:-1:1"})
    void corruptedMarkerIsNeverTreatedAsEmptyData(String marker) {
        prefs.put("items", marker);
        prefs.put("items.g0.0", "{}");
        prefs.put("items.g-1.0", "{}");
        assertThrows(InfrastructureException.class, store::load);
    }

    @Test
    void failedCommitRetainsLastCompleteConfiguration() {
        store.save(Map.of("old", "a".repeat(18000)));
        prefs.failNextFlush = true;
        assertThrows(InfrastructureException.class, () -> store.save(Map.of("new", "b".repeat(18000))));
        assertEquals(Map.of("old", "a".repeat(18000)), store.load());
    }

    @Test
    void failedInlineCommitRetainsPreviousValue() {
        store.save(Map.of("old", "saved"));
        prefs.failNextFlush = true;
        assertThrows(InfrastructureException.class, () -> store.save(Map.of("new", "unsaved")));
        assertEquals(Map.of("old", "saved"), store.load());
    }

    @Test
    void failedMarkerFlushKeepsPreviousChunksAndRestoresMarker() {
        var previous = Map.of("old", "x".repeat(18000));
        store.save(previous);
        prefs.failOnFlush = prefs.flushes + 2;
        assertThrows(InfrastructureException.class, () -> store.save(Map.of("new", "y".repeat(18000))));
        assertEquals(previous, store.load());
    }

    @Test
    void cleanupFailureDoesNotReportCommittedConfigurationAsFailed() {
        store.save(Map.of("old", "x".repeat(18000)));
        prefs.failOnFlush = prefs.flushes + 3;
        var next = Map.of("new", "y".repeat(18000));
        assertDoesNotThrow(() -> store.save(next));
        assertEquals(next, store.load());
    }

    static final class FailingPreferences extends AbstractPreferences {
        final Map<String, String> values = new HashMap<>();
        boolean failNextFlush;
        int flushes;
        int failOnFlush = -1;
        FailingPreferences() { super(null, ""); }
        protected void putSpi(String key, String value) { values.put(key, value); }
        protected String getSpi(String key) { return values.get(key); }
        protected void removeSpi(String key) { values.remove(key); }
        protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
        protected String[] childrenNamesSpi() { return new String[0]; }
        protected AbstractPreferences childSpi(String name) { throw new UnsupportedOperationException(); }
        protected void removeNodeSpi() { values.clear(); }
        protected void syncSpi() { }
        protected void flushSpi() throws BackingStoreException {
            flushes++;
            if (failNextFlush || flushes == failOnFlush) { failNextFlush = false; throw new BackingStoreException("Test storage failure"); }
        }
    }
}
