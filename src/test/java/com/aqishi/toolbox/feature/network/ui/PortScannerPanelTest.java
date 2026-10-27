package com.aqishi.toolbox.feature.network.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.aqishi.toolbox.feature.network.application.*;
import com.aqishi.toolbox.feature.network.domain.*;
import com.aqishi.toolbox.infra.network.scan.*;
import com.aqishi.toolbox.infra.secrets.InMemoryPreferences;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;

class PortScannerPanelTest {
    @TempDir Path temp;

    /** Panel cleanup deliberately runs off the EDT; TempDir must wait for its owned files. */
    @AfterEach
    void awaitResultStoreCleanup() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            try (var files = java.nio.file.Files.list(temp)) {
                if (files.noneMatch(path -> path.getFileName().toString().startsWith("scan-"))) return;
            }
            Thread.sleep(25);
        }
        fail("Scanner result store cleanup did not finish");
    }

    @Test
    void settingsPersistWithoutStartingAndNamedProfilesRestoreEffectivePorts() throws Exception {
        InMemoryPreferences preferences = new InMemoryPreferences();
        PortScannerPanel first = new PortScannerPanel(preferences, temp);
        try {
            SwingUtilities.invokeAndWait(
                    () -> {
                        first.getView();
                        clearGroups(first);
                        field(first, "customRangeField", JTextField.class)
                                .setText("22,80,443,8080-8082");
                        field(first, "timeoutSpinner", JSpinner.class).setValue(2500);
                        field(first, "threadSpinner", JSpinner.class).setValue(1);
                        first.saveSettings();
                        assertNull(field(first, "session", ScanSession.class));
                        field(first, "hostField", JTextField.class).setText("::/126");
                        field(first, "excludeTargets", JTextField.class).setText("::/128");
                        first.saveProfile("IPv6 test");
                        String placeholderName =
                                com.aqishi.toolbox.util.I18n.get("portscan.profileNone");
                        first.saveProfile(placeholderName);
                        assertTrue(
                                field(first, "profileCombo", JComboBox.class).getSelectedIndex()
                                        > 0);
                        first.deleteProfile(placeholderName);
                        field(first, "hostField", JTextField.class).setText("127.0.0.1");
                        field(first, "customRangeField", JTextField.class).setText("9999");
                        first.loadProfile("IPv6 test");
                        assertEquals(
                                "::/126", field(first, "hostField", JTextField.class).getText());
                        assertEquals(
                                "22,80,443,8080-8082",
                                field(first, "customRangeField", JTextField.class).getText());
                        assertNull(field(first, "session", ScanSession.class));
                    });
        } finally {
            SwingUtilities.invokeAndWait(first::closeResources);
        }
        PortScannerPanel second = new PortScannerPanel(preferences, temp);
        try {
            SwingUtilities.invokeAndWait(
                    () -> {
                        second.getView();
                        assertEquals(
                                2500, field(second, "timeoutSpinner", JSpinner.class).getValue());
                        assertEquals(1, field(second, "threadSpinner", JSpinner.class).getValue());
                        second.loadProfile("IPv6 test");
                        assertEquals(
                                "::/126", field(second, "hostField", JTextField.class).getText());
                        second.deleteProfile("IPv6 test");
                    });
            assertTrue(new PortScanPreferences(preferences).profiles().isEmpty());
        } finally {
            SwingUtilities.invokeAndWait(second::closeResources);
        }
    }

    @Test
    void invalidBatchAndInvalidFileLeaveThePreviousTargetUntouched() throws Exception {
        PortScannerPanel panel = new PortScannerPanel(new InMemoryPreferences(), temp);
        Path file = temp.resolve("targets.txt");
        java.nio.file.Files.writeString(file, "::1/128\n127.0.0.1-2\n");
        try {
            SwingUtilities.invokeAndWait(
                    () -> {
                        panel.getView();
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> panel.applyTargetText("127.0.0.1,broken/xyz"));
                        assertEquals(
                                "127.0.0.1", field(panel, "hostField", JTextField.class).getText());
                        panel.importTargets(file);
                    });
            await(() -> field(panel, "hostField", JTextField.class).getText().contains("::1/128"));
            assertNull(field(panel, "session", ScanSession.class));
            java.nio.file.Files.writeString(file, "256.1.1.1");
            String previous = field(panel, "hostField", JTextField.class).getText();
            SwingUtilities.invokeAndWait(() -> panel.importTargets(file));
            await(() -> field(panel, "startBtn", JButton.class).isEnabled());
            assertEquals(previous, field(panel, "hostField", JTextField.class).getText());
        } finally {
            SwingUtilities.invokeAndWait(panel::closeResources);
        }
    }

    @Test
    void targetImportBudgetIsCheckedAfterApplyingExclusions() throws Exception {
        PortScannerPanel panel = new PortScannerPanel(new InMemoryPreferences(), temp);
        try {
            SwingUtilities.invokeAndWait(
                    () -> {
                        panel.getView();
                        field(panel, "excludeTargets", JTextField.class)
                                .setText("::-ffff:ffff:ffff:ffff:ffff:ffff:ffff:fffb");
                        assertDoesNotThrow(() -> panel.applyTargetText("::/0"));
                        assertEquals("::/0", field(panel, "hostField", JTextField.class).getText());
                        assertNull(field(panel, "session", ScanSession.class));
                    });
        } finally {
            SwingUtilities.invokeAndWait(panel::closeResources);
        }
    }

    @Test
    void mixedTargetsAndPortsReachTheEngineAndResultsIncludeActualIp() throws Exception {
        AtomicReference<ScanPlan> captured = new AtomicReference<>();
        InMemoryPreferences preferences = new InMemoryPreferences();
        PortScannerPanel panel =
                new PortScannerPanel(preferences, temp) {
                    @Override
                    protected ScanSession createSession(ScanPlan plan) throws Exception {
                        captured.set(plan);
                        return new ScanSession(
                                plan,
                                new ScanPlanResolver(),
                                new TcpConnectProbe() {
                                    @Override
                                    public ProbeOutcome probe(
                                            ScanTargetParser.Target target, int port, int timeout) {
                                        return new ProbeOutcome(
                                                target.source(),
                                                target.ip(),
                                                port,
                                                ProbeOutcome.State.OPEN,
                                                20,
                                                "",
                                                0);
                                    }
                                },
                                new ScanResultRepository(temp, 16));
                    }
                };
        try {
            SwingUtilities.invokeAndWait(
                    () -> {
                        panel.getView();
                        clearGroups(panel);
                        field(panel, "hostField", JTextField.class)
                                .setText("127.0.0.1-2,127.0.0.1");
                        field(panel, "customRangeField", JTextField.class)
                                .setText("22,80,8080-8082,22");
                        field(panel, "rateSpinner", JSpinner.class).setValue(10000);
                        field(panel, "startBtn", JButton.class).doClick();
                        assertFalse(field(panel, "clearBtn", JButton.class).isEnabled());
                    });
            await(
                    () ->
                            field(panel, "startBtn", JButton.class).isEnabled()
                                    && field(panel, "resultTableModel", DefaultTableModel.class)
                                                    .getRowCount()
                                            == 10);
            assertEquals(2, captured.get().targets().numericCount());
            assertEquals(5, captured.get().ports().size());
            SwingUtilities.invokeAndWait(
                    () -> {
                        DefaultTableModel model =
                                field(panel, "resultTableModel", DefaultTableModel.class);
                        assertEquals("TCP", model.getValueAt(0, 3));
                        assertTrue(model.getValueAt(0, 1).toString().startsWith("127.0.0."));
                        field(panel, "hostTable", JTable.class).setRowSelectionInterval(0, 0);
                    });
            await(
                    () ->
                            field(panel, "resultTableModel", DefaultTableModel.class).getRowCount()
                                    == 5);
            assertEquals(64, preferences.getInt("portscan.threads", 0));
            assertFalse(preferences.raw().values().toString().contains("127.0.0."));
        } finally {
            SwingUtilities.invokeAndWait(panel::closeResources);
        }
    }

    @Test
    void invalidInputDoesNotStartAndStoppedResultsCannotPolluteNextSession() throws Exception {
        AtomicInteger created = new AtomicInteger();
        CountDownLatch firstProbe = new CountDownLatch(1);
        AtomicReference<CountDownLatch> release = new AtomicReference<>(new CountDownLatch(1));
        PortScannerPanel panel =
                new PortScannerPanel(new InMemoryPreferences(), temp) {
                    @Override
                    protected ScanSession createSession(ScanPlan plan) throws Exception {
                        int run = created.incrementAndGet();
                        CountDownLatch gate = release.get();
                        return new ScanSession(
                                plan,
                                new ScanPlanResolver(),
                                new TcpConnectProbe() {
                                    @Override
                                    public ProbeOutcome probe(
                                            ScanTargetParser.Target target, int port, int timeout) {
                                        if (run == 1) {
                                            firstProbe.countDown();
                                            try {
                                                gate.await();
                                            } catch (InterruptedException ignored) {
                                            }
                                        }
                                        return new ProbeOutcome(
                                                target.source(),
                                                target.ip(),
                                                port,
                                                ProbeOutcome.State.OPEN,
                                                0,
                                                "",
                                                0);
                                    }

                                    @Override
                                    public void close() {
                                        gate.countDown();
                                    }
                                },
                                new ScanResultRepository(temp, 16));
                    }
                };
        try {
            SwingUtilities.invokeAndWait(
                    () -> {
                        panel.getView();
                        clearGroups(panel);
                        field(panel, "customRangeField", JTextField.class).setText("80,wrong");
                        field(panel, "startBtn", JButton.class).doClick();
                        assertTrue(field(panel, "startBtn", JButton.class).isEnabled());
                        assertEquals(0, created.get());
                        field(panel, "customRangeField", JTextField.class).setText("1-100");
                        field(panel, "startBtn", JButton.class).doClick();
                    });
            assertTrue(firstProbe.await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> field(panel, "stopBtn", JButton.class).doClick());
            await(() -> field(panel, "startBtn", JButton.class).isEnabled());
            SwingUtilities.invokeAndWait(
                    () -> {
                        field(panel, "hostField", JTextField.class).setText("127.0.0.2");
                        field(panel, "customRangeField", JTextField.class).setText("12345");
                        field(panel, "startBtn", JButton.class).doClick();
                    });
            await(
                    () ->
                            created.get() == 2
                                    && field(panel, "startBtn", JButton.class).isEnabled()
                                    && field(panel, "resultTableModel", DefaultTableModel.class)
                                                    .getRowCount()
                                            == 1);
            SwingUtilities.invokeAndWait(
                    () -> {
                        assertEquals(
                                "127.0.0.2",
                                field(panel, "resultTableModel", DefaultTableModel.class)
                                        .getValueAt(0, 1));
                        assertEquals(
                                12345,
                                field(panel, "resultTableModel", DefaultTableModel.class)
                                        .getValueAt(0, 2));
                    });
        } finally {
            release.get().countDown();
            SwingUtilities.invokeAndWait(panel::closeResources);
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearGroups(PortScannerPanel panel) {
        field(panel, "presetChecks", Map.class)
                .values()
                .forEach(value -> ((JCheckBox) value).setSelected(false));
    }

    private static <T> T field(Object object, String name, Class<T> type) {
        try {
            Field f = PortScannerPanel.class.getDeclaredField(name);
            f.setAccessible(true);
            return type.cast(f.get(object));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        AtomicBoolean done = new AtomicBoolean();
        while (System.nanoTime() < end) {
            SwingUtilities.invokeAndWait(() -> done.set(condition.getAsBoolean()));
            if (done.get()) return;
            Thread.sleep(25);
        }
        fail("Scanner UI did not complete");
    }
}
