package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.feature.system.domain.OsFamily;
import com.aqishi.toolbox.feature.system.domain.PortEntry;
import com.aqishi.toolbox.feature.system.domain.PortSnapshot;
import com.aqishi.toolbox.feature.system.infra.PortProcessProbe;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortProcessPanelTest {

    /** 任何命令都「不存在」：面板测试绝不能真的去跑 netstat。 */
    private static PortProcessProbe offlineProbe() {
        return new PortProcessProbe(OsFamily.LINUX, (command, timeout) -> {
            throw new IOException("offline");
        }, ProcessHandle.current().pid(), false, StandardCharsets.UTF_8, Duration.ofMillis(100));
    }

    @Test
    void buildsViewWithCatalogIdentity() {
        PortProcessPanel panel = new PortProcessPanel();

        assertEquals("system", panel.getGroup());
        assertEquals("port.process", panel.getName());
        assertNotNull(panel.getView());
        panel.closeResources();
        panel.closeResources();
    }

    @Test
    void sortsNumericallyAndFiltersByProtocolAndText() throws Exception {
        PortProcessPanel panel = new PortProcessPanel(offlineProbe());
        List<PortEntry> entries = List.of(
                entry(PortEntry.Protocol.TCP, 9, 900, "sshd"),
                entry(PortEntry.Protocol.TCP, 10000, 1234, "java"),
                entry(PortEntry.Protocol.UDP, 53, 612, "systemd-resolve"),
                new PortEntry(PortEntry.Protocol.TCP, "0.0.0.0", 80, "0.0.0.0", PortEntry.NO_PORT, "LISTEN",
                        PortEntry.NO_PID, "", ""));
        SwingUtilities.invokeAndWait(() -> {
            panel.getView();
            panel.applySnapshot(new PortSnapshot(entries, PortSnapshot.Source.SS,
                    PortSnapshot.Limitation.MISSING_PROCESS_INFO, Instant.now()));
            JTable table = panel.table();
            assertEquals(4, table.getRowCount());

            // 端口列按数值排序：9 < 53 < 80 < 10000（按字符串会把 10000 排在 53 前面）。
            table.getRowSorter().toggleSortOrder(2);
            List<Object> ports = new ArrayList<>();
            for (int i = 0; i < table.getRowCount(); i++) {
                ports.add(table.getValueAt(i, 2));
            }
            assertEquals(List.of(9, 53, 80, 10000), ports);

            JCheckBox udp = panel.udpCheck();
            udp.doClick();
            assertEquals(3, table.getRowCount());
            udp.doClick();

            JTextField filter = panel.filterField();
            filter.setText("JAVA");
            assertEquals(1, table.getRowCount());
            assertEquals(1234L, table.getValueAt(0, 5));
            filter.setText("");

            // 选中行后刷新，同一条记录仍保持选中。
            table.setRowSelectionInterval(0, 0);
            Object selectedPort = table.getValueAt(0, 2);
            panel.applySnapshot(new PortSnapshot(entries, PortSnapshot.Source.SS,
                    PortSnapshot.Limitation.NONE, Instant.now()));
            assertTrue(table.getSelectedRow() >= 0);
            assertEquals(selectedPort, table.getValueAt(table.getSelectedRow(), 2));
        });
        panel.closeResources();
    }

    private static PortEntry entry(PortEntry.Protocol protocol, int port, long pid, String name) {
        return new PortEntry(protocol, "0.0.0.0", port, "0.0.0.0", PortEntry.NO_PORT,
                protocol == PortEntry.Protocol.TCP ? "LISTEN" : "", pid, name, "");
    }
}
