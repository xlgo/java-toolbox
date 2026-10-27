package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.data.ui.KafkaPanel;
import com.aqishi.toolbox.feature.data.ui.ZooKeeperPanel;
import com.aqishi.toolbox.feature.data.ui.EtcdPanel;
import com.aqishi.toolbox.feature.cloud.ui.DockerManagerPanel;
import com.aqishi.toolbox.feature.cloud.ui.K8sManagerPanel;
import com.aqishi.toolbox.feature.cloud.application.KubernetesServiceFactory;
import com.aqishi.toolbox.infra.secrets.InMemoryPreferences;
import com.aqishi.toolbox.infra.secrets.SecretStore;
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
                EtcdPanel etcd = new EtcdPanel();
                DockerManagerPanel docker = new DockerManagerPanel();
                K8sManagerPanel k8s = new K8sManagerPanel(KubernetesServiceFactory.standard(), SecretStore.disabled());
                assertNotNull(http.getView());
                assertNotNull(zooKeeper.getView());
                assertNotNull(etcd.getView());
                assertNotNull(docker.getView());
                assertNotNull(k8s.getView());
                assertFalse(getField(docker,"useSshCheck",JCheckBox.class).isSelected());
                assertFalse(getField(docker,"sshCombo",JComboBox.class).isEnabled());

                assertFalse(getField(http, "useSshCheck", JCheckBox.class).isSelected());
                assertFalse(getField(http, "sshCombo", JComboBox.class).isEnabled());
                assertFalse(getField(zooKeeper, "useSshCheck", JCheckBox.class).isSelected());
                assertFalse(getField(zooKeeper, "sshCombo", JComboBox.class).isEnabled());
                assertFalse(getField(etcd, "useSshCheck", JCheckBox.class).isSelected());
                JComboBox<?> etcdServers = getField(etcd, "sshCombo", JComboBox.class);
                JComboBox<?> zooKeeperServers = getField(zooKeeper, "sshCombo", JComboBox.class);
                assertFalse(etcdServers.isEnabled());
                assertEquals(zooKeeperServers.getItemCount(), etcdServers.getItemCount());
                for (int i = 0; i < etcdServers.getItemCount(); i++)
                    assertEquals(zooKeeperServers.getItemAt(i), etcdServers.getItemAt(i));
                for (Object panel : new Object[]{docker,k8s}) {
                    JComboBox<?> servers=getField(panel,"sshCombo",JComboBox.class);
                    assertEquals(etcdServers.getItemCount(),servers.getItemCount());
                    for(int i=0;i<servers.getItemCount();i++)assertEquals(etcdServers.getItemAt(i),servers.getItemAt(i));
                }
                docker.closeResources();k8s.closeResources();
                etcd.closeResources();
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
                KafkaPanel kafka = new KafkaPanel(SecretStore.disabled(), new InMemoryPreferences());
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
