package com.aqishi.toolbox.feature.cloud.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.aqishi.toolbox.feature.cloud.application.DockerService;
import com.aqishi.toolbox.feature.network.ssh.domain.SshConnectionConfig;
import com.aqishi.toolbox.infra.docker.DockerCommandRunner;
import com.aqishi.toolbox.infra.docker.DockerConnectionOptions;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.*;

class DockerManagerPanelTest {
    @Test
    void tunnelModeExposesTargetAndLocksSettingsWhileConnected() throws Exception {
        AtomicReference<DockerConnectionOptions> captured = new AtomicReference<>();
        DockerCommandRunner cli =
                new DockerCommandRunner() {
                    public Result run(List<String> args, int timeout) {
                        return new Result(
                                0,
                                args.contains("info")
                                        ? "{}"
                                        : "{\"ID\":\"fixture-id\",\"Names\":\"fixture\"}\n",
                                "");
                    }

                    public void cancel() {}

                    public void close() {}
                };
        DockerManagerPanel panel =
                new DockerManagerPanel(
                        (options, timeout) -> {
                            captured.set(options);
                            return new DockerService(cli, timeout);
                        });
        try {
            SwingUtilities.invokeAndWait(
                    () -> {
                        try {
                            panel.getView();
                            assertFalse(field(panel, "tunnelSettings", JPanel.class).isVisible());
                            field(panel, "useSshCheck", JCheckBox.class).doClick();
                            var config = new SshConnectionConfig();
                            config.setId("fixture-ssh");
                            config.setName("fixture");
                            @SuppressWarnings("unchecked")
                            JComboBox<SshConnectionConfig> servers =
                                    field(panel, "sshCombo", JComboBox.class);
                            servers.addItem(config);
                            servers.setSelectedItem(config);
                            field(panel, "contextField", JTextField.class).setText("desktop-linux");
                            field(panel, "sshModeCombo", JComboBox.class)
                                    .setSelectedItem(DockerConnectionOptions.SshMode.TCP_TUNNEL);
                            assertTrue(field(panel, "tunnelSettings", JPanel.class).isVisible());
                            assertFalse(field(panel, "contextField", JTextField.class).isEnabled());
                            field(panel, "tunnelHostField", JTextField.class).setText("10.0.0.12");
                            field(panel, "tunnelPortSpinner", JSpinner.class).setValue(32375);
                            field(panel, "connectBtn", JButton.class).doClick();
                        } catch (Exception error) {
                            throw new RuntimeException(error);
                        }
                    });
            await(() -> field(panel, "table", JTable.class).getRowCount() == 1);
            assertEquals("fixture-ssh", captured.get().sshId());
            assertEquals("10.0.0.12", captured.get().remoteHost());
            assertEquals(32375, captured.get().remotePort());
            assertEquals("", captured.get().context());
            assertEquals(DockerConnectionOptions.SshMode.TCP_TUNNEL, captured.get().sshMode());
            SwingUtilities.invokeAndWait(
                    () -> {
                        try {
                            assertFalse(field(panel, "sshModeCombo", JComboBox.class).isEnabled());
                            assertFalse(
                                    field(panel, "tunnelHostField", JTextField.class).isEnabled());
                            field(panel, "disconnectBtn", JButton.class).doClick();
                            field(panel, "sshModeCombo", JComboBox.class)
                                    .setSelectedItem(DockerConnectionOptions.SshMode.COMMAND);
                            assertTrue(field(panel, "contextField", JTextField.class).isEnabled());
                            assertEquals(
                                    "desktop-linux",
                                    field(panel, "contextField", JTextField.class).getText());
                        } catch (Exception error) {
                            throw new RuntimeException(error);
                        }
                    });
        } finally {
            SwingUtilities.invokeAndWait(panel::closeResources);
        }
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return type.cast(f.get(target));
    }

    @Test
    void connectsLoadsFiltersAndUsesFullSelectedResourceId() throws Exception {
        AtomicReference<List<String>> last = new AtomicReference<>();
        DockerCommandRunner runner =
                new DockerCommandRunner() {
                    public Result run(List<String> args, int timeout) {
                        last.set(args);
                        if (args.contains("info"))
                            return new Result(0, "{\"Name\":\"fixture\"}", "");
                        if (args.contains("ls"))
                            return new Result(
                                    0,
                                    "{\"ID\":\"full-id-123456789\",\"Names\":\"web\",\"Image\":\"nginx\",\"Status\":\"Up\",\"Ports\":\"8080\"}\n",
                                    "");
                        return new Result(0, "[{\"Id\":\"full-id-123456789\"}]", "");
                    }

                    public void cancel() {}

                    public void close() {}
                };
        DockerManagerPanel panel =
                new DockerManagerPanel((options, timeout) -> new DockerService(runner, timeout));
        try {
            SwingUtilities.invokeAndWait(
                    () -> {
                        assertNotNull(panel.getView());
                        try {
                            assertFalse(field(panel, "sshCombo", JComboBox.class).isEnabled());
                            field(panel, "connectBtn", JButton.class).doClick();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
            await(() -> field(panel, "table", JTable.class).getRowCount() == 1);
            SwingUtilities.invokeAndWait(
                    () -> {
                        try {
                            JTextField filter = field(panel, "filterField", JTextField.class);
                            filter.setText("missing");
                            assertEquals(0, field(panel, "table", JTable.class).getRowCount());
                            filter.setText("nginx");
                            JTable table = field(panel, "table", JTable.class);
                            assertEquals(1, table.getRowCount());
                            table.setRowSelectionInterval(0, 0);
                            field(panel, "inspectBtn", JButton.class).doClick();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
            await(() -> last.get().contains("inspect"));
            assertEquals("full-id-123456789", last.get().get(3));
        } finally {
            SwingUtilities.invokeAndWait(panel::closeResources);
        }
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean get() throws Exception;
    }

    private static void await(CheckedCondition condition) throws Exception {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        while (!done.get() && System.nanoTime() < end) {
            SwingUtilities.invokeAndWait(
                    () -> {
                        try {
                            done.set(condition.get());
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
            if (!done.get()) Thread.sleep(30);
        }
        assertTrue(done.get());
    }
}
