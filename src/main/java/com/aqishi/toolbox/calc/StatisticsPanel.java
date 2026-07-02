package com.aqishi.toolbox.calc;

import com.aqishi.toolbox.ui.ToolPanel;
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
        super("calc", "statistics",
                "均值", "中位数", "标准差", "方差", "Variance",
                "统计", "平均值", "极差", "总和", "最大", "最小");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 10));
        root.setBorder(UIUtils.CONTENT_PADDING);

        JTextArea input = new JTextArea(4, 40);
        input.setFont(UIUtils.monoFont());
        input.setText("12, 15, 18, 22, 25, 28, 30, 33, 36, 40");
        root.add(UIUtils.scrollText(input, "数据（逗号或空格分隔，支持换行）"), BorderLayout.NORTH);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton calc = UIUtils.button("计算统计量", 110);
        JButton sort = UIUtils.button("排序", 80);
        JButton clear = UIUtils.button("清空", 80);
        btns.add(calc); btns.add(sort); btns.add(clear);
        root.add(btns, BorderLayout.CENTER);

        JTextArea out = new JTextArea(12, 40);
        out.setFont(UIUtils.monoFont());
        out.setEditable(false);
        root.add(UIUtils.scrollText(out, "统计结果"), BorderLayout.SOUTH);

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
            sb.append(String.format("样本方差 s²  : %.4f\n", sq / (d.length - 1)));
            sb.append(String.format("样本标准差 s : %.4f\n", Math.sqrt(sq / (d.length - 1))));
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

    private static double[] parse(String text) {
        String[] parts = text.split("[,，\\s;；\n]+");
        java.util.List<Double> list = new java.util.ArrayList<>();
        for (String p : parts) {
            p = p.trim();
            if (p.isEmpty()) continue;
            try { list.add(Double.parseDouble(p)); }
            catch (NumberFormatException ignore) { }
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
