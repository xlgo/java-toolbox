package com.aqishi.toolbox.feature.compute.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;

/**
 * 统计计算面板：输入一组数字，输出计数、总和、均值、中位数、
 * 方差、标准差、最大、最小、极差。
 */
public class StatisticsPanel extends ToolPanel {

    public StatisticsPanel() {
        super(ToolCatalog.STATISTICS);
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ===== 数据卡片：输入高度自适应放 NORTH，三个动作贴在标题右侧，避免按钮行悬空 =====
        JTextArea input = Fields.area(4, 40);
        input.setText("12, 15, 18, 22, 25, 28, 30, 33, 36, 40");

        Card inputCard = Card.titled("数据", "逗号或空格分隔，支持换行");
        inputCard.setContent(Fields.scroll(input));

        JButton sort = Buttons.secondary("排序");
        JButton clear = Buttons.danger("清空");
        JButton calc = Buttons.primary("计算统计量");
        inputCard.addHeaderAction(sort);
        inputCard.addHeaderAction(clear);
        inputCard.addHeaderAction(calc);

        // ===== 结果卡片：十余行指标，交给 CENTER 吸收剩余高度 =====
        JTextArea out = Fields.output(12, 40);
        Card outCard = Card.flush("统计结果");
        outCard.setContent(Fields.scroll(out));

        root.add(inputCard, BorderLayout.NORTH);
        root.add(outCard, BorderLayout.CENTER);

        calc.addActionListener(e -> {
            double[] d = parse(input.getText());
            if (d.length == 0) { UIUtils.info(root, "请输入数据"); return; }
            double sum = 0, max = d[0], min = d[0];
            for (double v : d) { sum += v; if (v > max) max = v; if (v < min) min = v; }
            double mean = sum / d.length;
            double sq = 0;
            for (double v : d) sq += (v - mean) * (v - mean);
            double var = sq / d.length;                 // 总体方差
            double std = Math.sqrt(var);
            double[] sorted = d.clone();
            Arrays.sort(sorted);
            double median = sorted.length % 2 == 0
                    ? (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2.0
                    : sorted[sorted.length / 2];

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("数量 N      : %d\n", d.length));
            sb.append(String.format("总和 Σ      : %.4f\n", sum));
            sb.append(String.format("均值 μ      : %.4f\n", mean));
            sb.append(String.format("中位数      : %.4f\n", median));
            sb.append(String.format("最大值      : %.4f\n", max));
            sb.append(String.format("最小值      : %.4f\n", min));
            sb.append(String.format("极差        : %.4f\n", max - min));
            sb.append(String.format("总体方差 σ²  : %.4f\n", var));
            sb.append(String.format("总体标准差 σ : %.4f\n", std));
            // 样本方差除以 N-1，只有一个数据时没有定义，显示 N/A 而不是 NaN。
            String sampleVar = d.length > 1 ? String.format("%.4f", sq / (d.length - 1)) : "N/A";
            String sampleStd = d.length > 1 ? String.format("%.4f", Math.sqrt(sq / (d.length - 1))) : "N/A";
            sb.append(String.format("样本方差 s²  : %s\n", sampleVar));
            sb.append(String.format("样本标准差 s : %s\n", sampleStd));
            int skipped = countSkipped(input.getText());
            if (skipped > 0) {
                sb.append('\n').append(I18n.get("tool.statistics.skipped", skipped)).append('\n');
            }
            out.setText(sb.toString());
        });

        sort.addActionListener(e -> {
            double[] d = parse(input.getText());
            if (d.length == 0) return;
            Arrays.sort(d);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(trim(d[i]));
            }
            input.setText(sb.toString());
        });

        clear.addActionListener(e -> { input.setText(""); out.setText(""); });

        return root;
    }

    /** 统计被 {@link #parse} 当作非数字而跳过的片段数，结果里要告诉用户，否则 N 会悄悄变少。 */
    private static int countSkipped(String text) {
        int skipped = 0;
        for (String p : text.split("[,，\\s;；\n]+")) {
            p = p.trim();
            if (p.isEmpty()) continue;
            try { Double.parseDouble(p); } catch (NumberFormatException notNumber) { skipped++; }
        }
        return skipped;
    }

    private static double[] parse(String text) {
        String[] parts = text.split("[,，\\s;；\n]+");
        java.util.List<Double> list = new java.util.ArrayList<>();
        for (String p : parts) {
            p = p.trim();
            if (p.isEmpty()) continue;
            try { list.add(Double.parseDouble(p)); }
            catch (NumberFormatException ignore) { /* 非数字的片段直接跳过 */ }
        }
        double[] r = new double[list.size()];
        for (int i = 0; i < list.size(); i++) r[i] = list.get(i);
        return r;
    }

    private static String trim(double v) {
        if (v == (long) v) return String.valueOf((long) v);
        return String.valueOf(v);
    }
}
