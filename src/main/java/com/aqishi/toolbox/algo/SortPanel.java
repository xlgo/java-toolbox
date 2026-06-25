package com.aqishi.toolbox.algo;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * 排序算法可视化面板。
 * <p>支持冒泡、选择、插入、快排、归并五种算法，带逐帧动画与统计信息。</p>
 */
public class SortPanel extends ToolPanel {

    public SortPanel() {
        super("算法", "排序可视化");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ===== 控制区 =====
        JPanel ctrl = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JSlider size = new JSlider(8, 80, 30);
        size.setMajorTickSpacing(16);
        size.setPaintTicks(true);
        JComboBox<String> algo = new JComboBox<>(
                new String[]{"冒泡排序", "选择排序", "插入排序", "快速排序", "归并排序"});
        JButton gen = UIUtils.button("生成随机", 100);
        JButton start = UIUtils.button("开始排序", 100);
        JButton stop = UIUtils.button("停止", 80);
        JLabel speedL = new JLabel("速度");
        JSlider speed = new JSlider(1, 100, 50);
        ctrl.add(new JLabel("数量")); ctrl.add(size);
        ctrl.add(algo); ctrl.add(gen); ctrl.add(start); ctrl.add(stop);
        ctrl.add(speedL); ctrl.add(speed);
        root.add(ctrl, BorderLayout.NORTH);

        // ===== 画布 =====
        Canvas canvas = new Canvas();
        root.add(canvas, BorderLayout.CENTER);

        // ===== 状态栏 =====
        JLabel status = new JLabel("就绪");
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        root.add(status, BorderLayout.SOUTH);

        // ===== 状态 =====
        Timer[] timerHolder = new Timer[1];

        gen.addActionListener(e -> {
            int n = size.getValue();
            int[] arr = new int[n];
            for (int i = 0; i < n; i++) arr[i] = ThreadLocalRandom.current().nextInt(10, 100);
            canvas.setArray(arr, -1);
            status.setText("已生成 " + n + " 个随机数");
        });
        gen.doClick();

        start.addActionListener(e -> {
            if (timerHolder[0] != null && timerHolder[0].isRunning()) return;
            int[] arr = canvas.array.clone();
            Function<int[], Snapshot> sorter = pickSorter((String) algo.getSelectedItem());
            Snapshot snap = sorter.apply(arr);
            // 播放快照
            final List<int[]> frames = snap.frames;
            final int[] idx = {0};
            int delay = 101 - speed.getValue(); // 1..100ms
            Timer t = new Timer(Math.max(delay, 1), (ActionEvent ev) -> {
                if (idx[0] < frames.size()) {
                    canvas.setArray(frames.get(idx[0]).clone(), -1);
                    idx[0]++;
                } else {
                    ((Timer) ev.getSource()).stop();
                    canvas.setArray(arr, -1);
                    status.setText(String.format("完成 | 算法:%s | 比较:%d | 交换:%d | 耗时:%d ms",
                            algo.getSelectedItem(), snap.compares, snap.swaps, snap.elapsed));
                }
            });
            timerHolder[0] = t;
            t.start();
            status.setText("排序中…");
        });

        stop.addActionListener(e -> {
            if (timerHolder[0] != null) timerHolder[0].stop();
            status.setText("已停止");
        });

        return root;
    }

    private Function<int[], Snapshot> pickSorter(String name) {
        switch (name) {
            case "冒泡排序": return SortPanel::bubble;
            case "选择排序": return SortPanel::select;
            case "插入排序": return SortPanel::insert;
            case "快速排序": return SortPanel::quick;
            case "归并排序": return SortPanel::merge;
            default: return SortPanel::bubble;
        }
    }

    // ===== 排序实现，记录每一步快照 =====

    static final class Snapshot {
        List<int[]> frames = new ArrayList<>();
        long compares, swaps, elapsed;
    }

    private static Snapshot bubble(int[] a) {
        Snapshot s = new Snapshot();
        long t = System.nanoTime();
        int n = a.length;
        for (int i = 0; i < n - 1; i++) {
            for (int j = 0; j < n - 1 - i; j++) {
                s.compares++;
                if (a[j] > a[j + 1]) {
                    int tmp = a[j]; a[j] = a[j + 1]; a[j + 1] = tmp;
                    s.swaps++;
                    s.frames.add(a.clone());
                }
            }
        }
        s.elapsed = (System.nanoTime() - t) / 1_000_000;
        return s;
    }

    private static Snapshot select(int[] a) {
        Snapshot s = new Snapshot();
        long t = System.nanoTime();
        int n = a.length;
        for (int i = 0; i < n - 1; i++) {
            int min = i;
            for (int j = i + 1; j < n; j++) {
                s.compares++;
                if (a[j] < a[min]) min = j;
            }
            if (min != i) {
                int tmp = a[i]; a[i] = a[min]; a[min] = tmp;
                s.swaps++;
                s.frames.add(a.clone());
            }
        }
        s.elapsed = (System.nanoTime() - t) / 1_000_000;
        return s;
    }

    private static Snapshot insert(int[] a) {
        Snapshot s = new Snapshot();
        long t = System.nanoTime();
        for (int i = 1; i < a.length; i++) {
            int key = a[i], j = i - 1;
            while (j >= 0) {
                s.compares++;
                if (a[j] > key) {
                    a[j + 1] = a[j];
                    s.swaps++;
                    j--;
                } else break;
            }
            a[j + 1] = key;
            s.frames.add(a.clone());
        }
        s.elapsed = (System.nanoTime() - t) / 1_000_000;
        return s;
    }

    private static Snapshot quick(int[] a) {
        Snapshot s = new Snapshot();
        long t = System.nanoTime();
        quick0(a, 0, a.length - 1, s);
        s.elapsed = (System.nanoTime() - t) / 1_000_000;
        return s;
    }

    private static void quick0(int[] a, int lo, int hi, Snapshot s) {
        if (lo >= hi) return;
        int pivot = a[hi], i = lo - 1;
        for (int j = lo; j < hi; j++) {
            s.compares++;
            if (a[j] <= pivot) {
                i++;
                if (i != j) {
                    int tmp = a[i]; a[i] = a[j]; a[j] = tmp;
                    s.swaps++;
                    s.frames.add(a.clone());
                }
            }
        }
        int tmp = a[i + 1]; a[i + 1] = a[hi]; a[hi] = tmp;
        s.swaps++;
        s.frames.add(a.clone());
        quick0(a, lo, i, s);
        quick0(a, i + 2, hi, s);
    }

    private static Snapshot merge(int[] a) {
        Snapshot s = new Snapshot();
        long t = System.nanoTime();
        merge0(a, 0, a.length - 1, s);
        s.frames.add(a.clone());
        s.elapsed = (System.nanoTime() - t) / 1_000_000;
        return s;
    }

    private static void merge0(int[] a, int lo, int hi, Snapshot s) {
        if (lo >= hi) return;
        int mid = (lo + hi) >>> 1;
        merge0(a, lo, mid, s);
        merge0(a, mid + 1, hi, s);
        int[] tmp = Arrays.copyOfRange(a, lo, hi + 1);
        int i = 0, j = mid - lo + 1, k = lo;
        while (i <= mid - lo && j <= hi - lo) {
            s.compares++;
            if (tmp[i] <= tmp[j]) a[k++] = tmp[i++];
            else a[k++] = tmp[j++];
            s.swaps++;
        }
        while (i <= mid - lo) a[k++] = tmp[i++];
        while (j <= hi - lo) a[k++] = tmp[j++];
        s.frames.add(a.clone());
    }

    // ===== 画布 =====
    private static class Canvas extends JPanel {
        int[] array = new int[0];
        int highlight = -1;

        void setArray(int[] a, int hi) {
            this.array = a;
            this.highlight = hi;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (array.length == 0) return;
            int w = getWidth(), h = getHeight();
            int gap = 2;
            int bw = Math.max(1, (w - gap * (array.length - 1)) / array.length);
            int max = 1;
            for (int v : array) max = Math.max(max, v);
            for (int i = 0; i < array.length; i++) {
                int bh = (int) ((double) array[i] / max * (h - 10));
                g.setColor(i == highlight ? Color.RED : new Color(66, 133, 244));
                int x = i * (bw + gap);
                g.fillRect(x, h - bh, bw, bh);
            }
        }
    }
}
