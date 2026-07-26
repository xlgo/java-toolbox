package com.aqishi.toolbox.algo;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;

/**
 * 查找算法面板：二分查找（自动排序后演示区间收缩过程）+ 线性查找。
 */
public class SearchPanel extends ToolPanel {

    public SearchPanel() {
        super("algo", "search.algorithm",
                "二分查找", "Binary Search", "线性查找", "Linear Search",
                "搜索算法", "查找");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ===== 参数卡片：三个输入按表单列对齐，比原来一排 FlowLayout 更容易读 =====
        JTextField arrField = Fields.mono("3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5");
        JTextField target = Fields.mono("5");
        JComboBox<String> mode = Fields.combo(new String[]{"二分查找", "线性查找"}, 140);
        JButton run = Buttons.primary("查找");

        FormGrid form = new FormGrid();
        form.row("数组(逗号分隔)", arrField);
        // 目标值与算法都是窄控件，靠左包一层，免得被表单的水平填充拉成整行宽
        form.row("目标", leading(target));
        form.row("算法", leading(mode));
        form.caption("二分查找会先把数组排序，再逐步输出区间收缩过程");

        Card config = Card.titled("查找参数");
        config.setContent(form);
        config.addHeaderAction(run);

        // ===== 过程卡片：逐步日志行数多，放 CENTER 独占剩余高度 =====
        // 行数只决定首选高度，实际高度由 CENTER 拉伸；取小值免得空结果区就先冒出滚动条
        JTextArea out = Fields.output(10, 50);
        Card process = Card.flush("查找过程");
        process.setContent(Fields.scroll(out));

        root.add(config, BorderLayout.NORTH);
        root.add(process, BorderLayout.CENTER);

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

    /**
     * 把窄控件左对齐地包一层，使其在 FormGrid 里保留自身首选宽度。
     *
     * <p>{@code FlowLayout} 的 hgap 会同时留在行首，导致包过的控件比同列的普通输入框右移，
     * 所以这里 hgap 取 0，控件之间的间距改用 strut 补。</p>
     */
    private static JPanel leading(Component... items) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        panel.setOpaque(false);
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                panel.add(Box.createHorizontalStrut(Tokens.SPACE_SM));
            }
            panel.add(items[i]);
        }
        return panel;
    }
}
