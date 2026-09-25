package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.feature.system.domain.ThreadDumpAnalyzer;
import com.aqishi.toolbox.feature.system.domain.ThreadDumpParser;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadDumpPanelTest {

    @Test
    void buildsViewWithCatalogIdentity() {
        ThreadDumpPanel panel = new ThreadDumpPanel();

        assertEquals("system", panel.getGroup());
        assertEquals("thread.dump", panel.getName());
        assertNotNull(panel.getView());
        panel.closeResources();
    }

    private static ThreadDumpAnalyzer.Report analyze(String resource) throws IOException {
        try (InputStream in = ThreadDumpPanelTest.class.getResourceAsStream("/threaddump/" + resource)) {
            String text = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            return new ThreadDumpAnalyzer().analyze(new ThreadDumpParser().parse(text));
        }
    }

    @Test
    void populatesTabsForMultiDumpThenSingleDumpReport() throws Exception {
        ThreadDumpPanel panel = new ThreadDumpPanel();
        ThreadDumpAnalyzer.Report multi = analyze("two-dumps.txt");
        ThreadDumpAnalyzer.Report single = analyze("deadlock-monitor.txt");
        SwingUtilities.invokeAndWait(() -> {
            JComponent view = panel.getView();
            JTabbedPane tabs = find(view, JTabbedPane.class);
            assertNotNull(tabs);

            panel.applyReport(multi);
            assertTrue(tabs.isEnabledAt(6));
            // 两份转储：线程名、nid、#1、#2、栈未变、疑似卡死。
            JTable across = find((JComponent) tabs.getComponentAt(6), JTable.class);
            assertEquals(6, across.getColumnCount());
            assertEquals(6, across.getRowCount());
            // 疑似卡死的排最前，同为疑似时按名字：main（读 stdin 不在空闲列表里）、report-1。
            assertEquals("main", across.getValueAt(0, 0));
            assertEquals("report-1", across.getValueAt(1, 0));

            panel.applyReport(single);
            assertFalse(tabs.isEnabledAt(6));
            JTable threads = find((JComponent) tabs.getComponentAt(0), JTable.class);
            assertEquals(14, threads.getRowCount());
            threads.setRowSelectionInterval(0, 0);
            assertTrue(tabs.getTitleAt(1).endsWith("(1)"));

            panel.applyReport(null);
            assertEquals(0, threads.getRowCount());
        });
        panel.closeResources();
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container) {
                T nested = find((Container) child, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    @Test
    void buildsPlainTextReport() throws IOException {
        ThreadDumpAnalyzer.Report report = analyze("two-dumps.txt");
        String rendered = new ThreadDumpPanel().buildReport(report);

        // 只断言不经过 I18n 参数格式化的部分，避免依赖资源文件内容。
        assertTrue(rendered.contains("\"report-1\"  BLOCKED  at com.example.Report.render"), rendered);
        assertTrue(rendered.contains("report-#  1  [BLOCKED 1]"), rendered);
        assertTrue(rendered.contains("1  com.example.Task.compute"), rendered);
    }
}
