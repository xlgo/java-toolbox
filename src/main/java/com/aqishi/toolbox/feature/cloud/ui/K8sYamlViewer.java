package com.aqishi.toolbox.feature.cloud.ui;

import com.aqishi.toolbox.ui.kit.ActionBar;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.util.UIUtils;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 资源 YAML 查看窗口：折叠树视图与原始 YAML 文本并排成页签。
 *
 * <p>从 {@code K8sManagerPanel} 拆出的纯展示组件，不依赖集群连接状态。</p>
 */
final class K8sYamlViewer {

    private static final String[] BRACKET_COLORS = {
            "#C768DB", "#2D9CDB", "#F2C94C", "#6FCF97"
    };

    private K8sYamlViewer() {
    }

    static void show(String resourceName, String yaml, JsonNode jsonNode) {
        JDialog dialog = new JDialog((Frame) null, "查看: " + resourceName, true);
        dialog.setSize(750, 580);
        dialog.setLocationRelativeTo(null);

        JTabbedPane tabs = new JTabbedPane();

        // Tab 1: 折叠树视图
        JTree tree = new JTree(new DefaultMutableTreeNode("Resource"));
        tree.setFont(UIUtils.monoFont());
        tree.putClientProperty("JTree.lineStyle", "None");
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(20);

        DefaultTreeCellRenderer renderer = new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel,
                                                          boolean expanded, boolean leaf, int r, boolean hasFocus) {
                super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, r, hasFocus);
                if (value instanceof DefaultMutableTreeNode) {
                    Object userObj = ((DefaultMutableTreeNode) value).getUserObject();
                    if (userObj instanceof FolderNode) {
                        FolderNode node = (FolderNode) userObj;
                        setText(expanded ? node.openText : node.closeText);
                    }
                }
                if (sel) {
                    setBackground(UIManager.getColor("List.selectionBackground"));
                    setForeground(UIManager.getColor("List.selectionForeground"));
                } else {
                    setBackground(null);
                    setForeground(null);
                }
                return this;
            }
        };
        renderer.setOpenIcon(null);
        renderer.setClosedIcon(null);
        renderer.setLeafIcon(null);
        tree.setCellRenderer(renderer);

        if (jsonNode != null) {
            DefaultMutableTreeNode rootTreeNode = toTreeNode(jsonNode, resourceName, 0, true);
            tree.setModel(new DefaultTreeModel(rootTreeNode));
            // 默认展开前几层
            for (int i = 0; i < Math.min(tree.getRowCount(), 25); i++) {
                tree.expandRow(i);
            }
        }

        JScrollPane treeScroll = Fields.scroll(tree);
        tabs.addTab("折叠树视图 (Collapsible Tree)", treeScroll);

        // Tab 2: 原始 YAML 文本
        JTextArea area = new JTextArea(yaml);
        area.setEditable(false);
        tabs.addTab("YAML 文本 (Raw YAML)", UIUtils.scrollText(area, "YAML / JSON 内容"));

        JButton copyBtn = Buttons.secondary("复制 YAML");
        copyBtn.addActionListener(e -> {
            UIUtils.copyToClipboard(yaml);
            UIUtils.info(dialog, "已成功复制到剪贴板！");
        });
        JButton closeBtn = Buttons.ghost("关闭");
        closeBtn.addActionListener(e -> dialog.dispose());
        ActionBar bottom = new ActionBar();
        bottom.right(copyBtn);
        bottom.right(closeBtn);

        tabs.setBorder(null);
        JPanel content = Layouts.page();
        content.add(tabs, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setVisible(true);
    }

    private static DefaultMutableTreeNode toTreeNode(JsonNode node, String keyName, int depth, boolean isLast) {
        String keyHtml = keyName.isEmpty() ? "" : "<span style='color:#e06c75'>\"" + keyName + "\"</span>: ";
        String comma = isLast ? "" : "<span style='color:#abb2bf'>,</span>";
        String color = BRACKET_COLORS[depth % BRACKET_COLORS.length];

        if (node.isObject()) {
            String open = "<html>" + keyHtml + "<span style='color:" + color + "'><b>{</b></span></html>";
            String close = "<html>" + keyHtml + "<span style='color:" + color + "'><b>{ ... }</b></span>" + comma + "</html>";

            DefaultMutableTreeNode container = new DefaultMutableTreeNode(new FolderNode(open, close));

            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            List<Map.Entry<String, JsonNode>> list = new ArrayList<>();
            while (fields.hasNext()) {
                list.add(fields.next());
            }

            for (int i = 0; i < list.size(); i++) {
                Map.Entry<String, JsonNode> field = list.get(i);
                boolean lastField = (i == list.size() - 1);
                container.add(toTreeNode(field.getValue(), field.getKey(), depth + 1, lastField));
            }

            String endText = "<html><span style='color:" + color + "'><b>}</b></span>" + comma + "</html>";
            container.add(new DefaultMutableTreeNode(new FolderNode(endText, endText)));
            return container;

        } else if (node.isArray()) {
            String open = "<html>" + keyHtml + "<span style='color:" + color + "'><b>[</b></span></html>";
            String close = "<html>" + keyHtml + "<span style='color:" + color + "'><b>[ ... ]</b></span>" + comma + "</html>";

            DefaultMutableTreeNode container = new DefaultMutableTreeNode(new FolderNode(open, close));

            for (int i = 0; i < node.size(); i++) {
                boolean lastField = (i == node.size() - 1);
                container.add(toTreeNode(node.get(i), "", depth + 1, lastField));
            }

            String endText = "<html><span style='color:" + color + "'><b>]</b></span>" + comma + "</html>";
            container.add(new DefaultMutableTreeNode(new FolderNode(endText, endText)));
            return container;
        } else {
            String valHtml;
            if (node.isTextual()) {
                valHtml = "<span style='color:#98c311'>\"" + escapeHtml(node.asText()) + "\"</span>";
            } else if (node.isNumber()) {
                valHtml = "<span style='color:#d19a66'>" + node + "</span>";
            } else if (node.isBoolean()) {
                valHtml = "<span style='color:#d19a66'><b>" + node + "</b></span>";
            } else {
                valHtml = "<span style='color:#abb2bf'>null</span>";
            }

            String text = "<html>" + keyHtml + valHtml + comma + "</html>";
            return new DefaultMutableTreeNode(new FolderNode(text, text));
        }
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /** 可折叠节点的显示文本：展开与收起使用不同文案。 */
    private static final class FolderNode {
        final String openText;
        final String closeText;

        FolderNode(String openText, String closeText) {
            this.openText = openText;
            this.closeText = closeText;
        }

        @Override
        public String toString() {
            return openText;
        }
    }
}
