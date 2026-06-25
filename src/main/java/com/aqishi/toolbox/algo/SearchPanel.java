package com.aqishi.toolbox.algo;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;

/**
 * 查找算法面板：二分查找（自动排序后演示区间收缩过程）+ 线性查找。
 */
public class SearchPanel extends ToolPanel {

    public SearchPanel() {
        super("算法", "查找算法");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 10));
        root.setBorder(UIUtils.CONTENT_PADDING);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JTextField arrField = new JTextField("3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5", 24);
        JTextField target = new JTextField("5", 6);
        JComboBox<String> mode = new JComboBox<>(new String[]{"二分查找", "线性查找"});
        JButton run = UIUtils.button("查找", 80);
        top.add(new JLabel("数组(逗号分隔)")); top.add(arrField);
        top.add(new JLabel("目标")); top.add(target);
        top.add(mode); top.add(run);
        root.add(top, BorderLayout.NORTH);

        JTextArea out = new JTextArea(14, 50);
        out.setFont(UIUtils.monoFont());
        out.setEditable(false);
        root.add(UIUtils.scrollText(out, "查找过程"), BorderLayout.CENTER);

        run.addActionListener(e -> {
            try {
                String[] parts = arrField.getText().split("[,，\\s]+");
                int[] arr = new int[parts.length];
                for (int i = 0; i < parts.length; i++) arr[i] = Integer.parseInt(parts[i].trim());
                int t = Integer.parseInt(target.getText().trim());
                StringBuilder sb = new StringBuilder();
                if ("二分查找".equals(mode.getSelectedItem())) {
                    sb.append("二分查找需要有序数组，先排序：\n原始: ").append(Arrays.toString(arr)).append('\n');
                    Arrays.sort(arr);
                    sb.append("排序后: ").append(Arrays.toString(arr)).append('\n');
                    int lo = 0, hi = arr.length - 1, cmpCount = 0;
                    boolean found = false;
                    while (lo <= hi) {
                        int mid = (lo + hi) >>> 1;
                        cmpCount++;
                        sb.append(String.format("  比较 arr[%d]=%d 与 %d → 区间[%d,%d]\n",
                                mid, arr[mid], t, lo, hi));
                        if (arr[mid] == t) {
                            sb.append("命中！索引=").append(mid).append("，比较次数=").append(cmpCount).append('\n');
                            found = true;
                            break;
                        } else if (arr[mid] < t) lo = mid + 1;
                        else hi = mid - 1;
                    }
                    if (!found) sb.append("未找到，比较次数=").append(cmpCount).append('\n');
                } else {
                    sb.append("线性查找：\n");
                    boolean found = false;
                    for (int i = 0; i < arr.length; i++) {
                        sb.append(String.format("  比较 arr[%d]=%d 与 %d\n", i, arr[i], t));
                        if (arr[i] == t) {
                            sb.append("命中！索引=").append(i).append('\n');
                            found = true;
                            break;
                        }
                    }
                    if (!found) sb.append("未找到\n");
                }
                out.setText(sb.toString());
            } catch (Exception ex) {
                UIUtils.error(root, "输入有误：" + ex.getMessage());
            }
        });

        return root;
    }
}
