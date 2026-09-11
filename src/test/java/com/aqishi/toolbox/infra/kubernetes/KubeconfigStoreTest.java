package com.aqishi.toolbox.infra.kubernetes;

import com.aqishi.toolbox.domain.KubernetesProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

class KubeconfigStoreTest {

    private Preferences node;
    private KubeconfigStore store;

    @BeforeEach
    void setUp() {
        node = Preferences.userRoot().node("toolbox-test-" + UUID.randomUUID());
        store = new KubeconfigStore(node);
    }

    @AfterEach
    void tearDown() throws Exception {
        node.removeNode();
    }

    @Test
    void saveSingleProfileAndListInNavigationOrder() {
        store.save(new KubernetesProfile("zeta", "https://z:6443", "tz", false));
        store.save(new KubernetesProfile("alpha", "https://a:6443", "ta", true));

        List<KubernetesProfile> profiles = store.list();
        assertEquals(List.of("zeta", "alpha"),
                profiles.stream().map(p -> p.name).toList());
        assertTrue(profiles.get(1).skipTls);
    }

    @Test
    void savingSameNameTwiceOverwritesInPlace() {
        store.save(new KubernetesProfile("one", "https://old:6443", "t1", false));
        store.save(new KubernetesProfile("two", "https://two:6443", "t2", false));
        store.save(new KubernetesProfile("one", "https://new:6443", "t3", false));

        List<KubernetesProfile> profiles = store.list();
        assertEquals(List.of("one", "two"), profiles.stream().map(p -> p.name).toList());
        assertEquals("https://new:6443", profiles.get(0).serverUrl);
    }

    @Test
    void saveRejectsBlankProfileName() {
        assertThrows(IllegalArgumentException.class,
                () -> store.save(new KubernetesProfile("  ", "https://x:6443", "t", false)));
        assertThrows(IllegalArgumentException.class, () -> store.save((KubernetesProfile) null));
    }

    @Test
    void deleteRemovesProfileAndIgnoresBlankId() {
        store.save(new KubernetesProfile("one", "https://o:6443", "t", false));
        store.save(new KubernetesProfile("two", "https://t:6443", "t", false));

        store.delete("one");
        assertEquals(List.of("two"), store.list().stream().map(p -> p.name).toList());

        store.delete("  ");
        store.delete(null);
        assertEquals(1, store.list().size());
    }

    @Test
    void listIsUnmodifiable() {
        store.save(new KubernetesProfile("one", "https://o:6443", "t", false));
        assertThrows(UnsupportedOperationException.class,
                () -> store.list().add(new KubernetesProfile()));
    }
}
