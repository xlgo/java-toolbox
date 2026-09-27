package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.feature.system.domain.GcAnalyzer;
import com.aqishi.toolbox.feature.system.domain.GcLogParser;
import com.aqishi.toolbox.feature.system.domain.GcReport;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcLogPanelTest {

    @Test
    void buildsViewWithCatalogIdentity() {
        GcLogPanel panel = new GcLogPanel();
        assertEquals("system", panel.getGroup());
        assertEquals("gc.log", panel.getName());
        assertNotNull(panel.getView());
        panel.closeResources();
        panel.closeResources();
    }

    static GcReport analyze(String resource) throws IOException {
        try (InputStream in = GcLogPanelTest.class.getResourceAsStream("/gclog/" + resource)) {
            String text = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            return new GcAnalyzer().analyze(new GcLogParser().parse(text));
        }
    }

    @Test
    void appliesReportToTabsAndChartsOnEdt() throws Exception {
        GcLogPanel panel = new GcLogPanel();
        GcReport g1 = analyze("g1-jdk17.txt");
        GcReport zgc = analyze("zgc-jdk17.txt");
        SwingUtilities.invokeAndWait(() -> {
            JComponent view = panel.getView();
            JTabbedPane tabs = find(view, JTabbedPane.class);
            assertNotNull(tabs);
            assertEquals(7, tabs.getTabCount());

            panel.applyReport(g1);
            List<JTable> pauseTables = findAll((Container) tabs.getComponentAt(1), JTable.class);
            assertEquals(2, pauseTables.size());
            // 按类型统计的表 + 逐次停顿表
            assertEquals(g1.byType().size(), pauseTables.get(0).getRowCount());
            assertEquals(11, pauseTables.get(1).getRowCount());
            JTable causes = find((Container) tabs.getComponentAt(2), JTable.class);
            assertEquals(g1.causes().size(), causes.getRowCount());
            JTable advice = find((Container) tabs.getComponentAt(5), JTable.class);
            assertEquals(g1.advice().size(), advice.getRowCount());
            assertTrue(tabs.getTitleAt(5).endsWith("(" + g1.advice().size() + ")"));
            assertFalse(tabs.isEnabledAt(4), "no safepoint lines in this fixture");
            // 排序与选中不应抛异常。
            pauseTables.get(1).getRowSorter().toggleSortOrder(5);
            pauseTables.get(1).setRowSelectionInterval(0, 0);

            paintCharts(view);

            panel.applyReport(zgc);
            assertEquals(9, pauseTables.get(1).getRowCount());
            paintCharts(view);

            panel.applyReport(null);
            assertEquals(0, pauseTables.get(1).getRowCount());
            assertEquals(0, advice.getRowCount());
            paintCharts(view);
        });
        panel.closeResources();
    }

    @Test
    void safepointTabEnabledWhenPresent() throws Exception {
        GcLogPanel panel = new GcLogPanel();
        GcReport report = analyze("safepoint-pid-tid.txt");
        SwingUtilities.invokeAndWait(() -> {
            JTabbedPane tabs = find(panel.getView(), JTabbedPane.class);
            panel.applyReport(report);
            assertTrue(tabs.isEnabledAt(4));
            JTable operations = find((Container) tabs.getComponentAt(4), JTable.class);
            assertEquals(4, operations.getRowCount());
        });
        panel.closeResources();
    }

    private static void paintCharts(JComponent view) {
        List<GcChart> charts = findAll(view, GcChart.class);
        assertEquals(2, charts.size());
        for (GcChart chart : charts) {
            chart.setSize(800, 260);
            BufferedImage image = new BufferedImage(800, 260, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            chart.paint(graphics);
            graphics.dispose();
        }
    }

    @Test
    void buildsPlainTextReport() throws IOException {
        String text = GcReportText.build(analyze("g1-jdk17.txt"));
        // 只断言不经过 I18n 参数格式化的部分，避免依赖资源文件内容。
        assertTrue(text.contains("Pause Full"), text);
        assertTrue(text.contains("G1 Compaction Pause"), text);
        assertTrue(text.contains("300.0 ms"), text);
    }

    @Test
    void niceTicksAndLabels() {
        assertEquals(20, GcChart.niceStep(100, 5), 1e-9);
        assertEquals(0.5, GcChart.niceStep(2.2, 5), 1e-9);
        assertEquals(1000, GcChart.niceStep(7000, 10), 1e-9);
        assertEquals("0.3", GcChart.trim(0.30000000000000004, 0.1));
        assertEquals("120", GcChart.trim(120.0, 20));
        assertEquals("a &lt;b&gt; &amp;", GcChart.escape("a <b> &"));
        assertEquals("1.5 MB", GcFormat.size(1536));
        assertEquals("2.00 GB", GcFormat.size(2 * 1024 * 1024));
        assertEquals("90.0 min", GcFormat.duration(5400));
        assertEquals("-", GcFormat.ms(Double.NaN));
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        List<T> all = findAll(root, type);
        return all.isEmpty() ? null : all.get(0);
    }

    private static <T extends Component> List<T> findAll(Container root, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                result.add(type.cast(child));
            }
            if (child instanceof Container) {
                result.addAll(findAll((Container) child, type));
            }
        }
        return result;
    }
}
