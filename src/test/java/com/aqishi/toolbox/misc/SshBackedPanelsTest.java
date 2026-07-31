package com.aqishi.toolbox.misc;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SshBackedPanelsTest {

    @Test
    void httpAndZooKeeperViewsBuildOnTheEventDispatchThread() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                HttpTestPanel http = new HttpTestPanel();
                ZooKeeperPanel zooKeeper = new ZooKeeperPanel();
                assertNotNull(http.getView());
                assertNotNull(zooKeeper.getView());

                assertFalse(getField(http, "useSshCheck", JCheckBox.class).isSelected());
                assertFalse(getField(http, "sshCombo", JComboBox.class).isEnabled());
                assertFalse(getField(zooKeeper, "useSshCheck", JCheckBox.class).isSelected());
                assertFalse(getField(zooKeeper, "sshCombo", JComboBox.class).isEnabled());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        assertNull(failure.get(), "SSH-backed panels should build without an active connection");
    }

    @Test
    void kafkaUses9093AndKeepsTunnelSelectorDisabledUntilOptIn() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                KafkaPanel kafka = new KafkaPanel();
                assertNotNull(kafka.getView());
                assertEquals("127.0.0.1:9093", getField(kafka, "serversField", JTextField.class).getText());
                assertFalse(getField(kafka, "useSshCheck", JCheckBox.class).isSelected());
                assertFalse(getField(kafka, "sshCombo", JComboBox.class).isEnabled());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        assertNull(failure.get(), "Kafka tunnel controls should be opt-in");
    }

    private static <T> T getField(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }
}
