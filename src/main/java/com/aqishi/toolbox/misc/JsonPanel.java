package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.io.IOException;

/**
 * JSON 格式化 / 压缩面板：支持直接在生成结果中进行行内折叠，带彩虹括号和语法高亮。
 */
public class JsonPanel extends ToolPanel {

    private CardLayout cardLayout;
    private JPanel outputCardPanel;

    private JTree prettyTree;        // 美化视图（可折叠的代码树）
    private JTextPane compactPane;   // 压缩视图（单行文本）

    private String lastJson = "";

    // 彩虹括号颜色（粉紫、天蓝、橙黄、翠绿）
    private static final String[] BRACKET_COLORS = {
            "#C768DB", "#2D9CDB", "#F2C94C", "#6FCF97"
    };

    public JsonPanel() {
        super("format", "json.format",
                "JSON", "美化", "压缩", "格式化",
                "Json美化", "Json压缩", "格式化JSON");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // 按钮栏
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton pretty = UIUtils.button("美化", 80);
        JButton compact = UIUtils.button("压缩", 80);
        JButton copy = UIUtils.button("复制结果", 100);
        JButton clear = UIUtils.button("清空", 80);
        btns.add(pretty); btns.add(compact); btns.add(copy); btns.add(clear);
        root.add(btns, BorderLayout.NORTH);

        // 输入区域
        JTextArea input = new JTextArea(6, 40);
        input.setFont(UIUtils.monoFont());
        input.setText("{\n  \"projectName\": \"JavaToolbox\",\n  \"version\": \"1.2.0\",\n  \"active\": true,\n  \"server\": {\n    \"port\": 8080,\n    \"host\": \"localhost\",\n    \"enableTls\": false,\n    \"sslConfig\": null\n  },\n  \"modules\": [\n    {\n      \"id\": \"bpmn\",\n      \"name\": \"BPMN 2.0 Designer\",\n      \"tags\": [\"workflow\", \"editor\", \"xml\"]\n    },\n    {\n      \"id\": \"k8s\",\n      \"name\": \"Kubernetes Generator\",\n      \"tags\": [\"yaml\", \"k8s\", \"deploy\"]\n    }\n  ],\n  \"systemMetrics\": {\n    \"cpu\": {\n      \"cores\": 8,\n      \"loadPercent\": 24.5\n    },\n    \"memory\": {\n      \"totalGb\": 16,\n      \"usedGb\": 6.2\n    }\n  }\n}");

        // 输出区域：使用 CardLayout 来切换折叠树和普通单行文本
        cardLayout = new CardLayout();
        outputCardPanel = new JPanel(cardLayout);

        // 1. 美化可折叠代码树卡片
        prettyTree = new JTree(new DefaultMutableTreeNode("JSON"));
        prettyTree.setFont(UIUtils.monoFont());
        prettyTree.putClientProperty("JTree.lineStyle", "None");
        prettyTree.setRootVisible(true);
        prettyTree.setShowsRootHandles(true);
        prettyTree.setRowHeight(20);
        prettyTree.setCellRenderer(new CodeTreeCellRenderer(prettyTree));
        
        // 隐藏树节点的默认边框和图标，与编辑器界面融为一体
        DefaultTreeCellRenderer renderer = (DefaultTreeCellRenderer) prettyTree.getCellRenderer();
        renderer.setOpenIcon(null);
        renderer.setClosedIcon(null);
        renderer.setLeafIcon(null);

        JScrollPane treeScroll = new JScrollPane(prettyTree);
        treeScroll.setBorder(BorderFactory.createTitledBorder("结果 (点击左侧三角箭头折叠/展开)"));
        outputCardPanel.add(treeScroll, "PRETTY");

        // 2. 压缩视图卡片
        compactPane = new JTextPane();
        compactPane.setEditable(false);
        compactPane.setFont(UIUtils.monoFont());
        JScrollPane textScroll = new JScrollPane(compactPane);
        textScroll.setBorder(BorderFactory.createTitledBorder("结果 (压缩)"));
        outputCardPanel.add(textScroll, "COMPACT");

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                UIUtils.scrollText(input, "输入 JSON"),
                outputCardPanel);
        split.setResizeWeight(0.35);
        root.add(split, BorderLayout.CENTER);

        pretty.addActionListener(e -> {
            String jsonText = input.getText().trim();
            if (jsonText.isEmpty()) return;
            try {
                // 校验并构建树
                buildJsonTree(jsonText);
                lastJson = JsonFormatter.pretty(jsonText);
                cardLayout.show(outputCardPanel, "PRETTY");
            } catch (Exception ex) {
                UIUtils.error(root, "JSON 解析出错：" + ex.getMessage());
            }
        });

        compact.addActionListener(e -> {
            String jsonText = input.getText().trim();
            if (jsonText.isEmpty()) return;
            try {
                lastJson = JsonFormatter.compact(jsonText);
                compactPane.setText(lastJson);
                cardLayout.show(outputCardPanel, "COMPACT");
            } catch (Exception ex) {
                UIUtils.error(root, "JSON 解析出错：" + ex.getMessage());
            }
        });

        copy.addActionListener(e -> {
            if (!lastJson.isEmpty()) {
                UIUtils.copyToClipboard(lastJson);
            }
        });

        clear.addActionListener(e -> {
            input.setText("");
            compactPane.setText("");
            lastJson = "";
            prettyTree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("JSON")));
        });

        pretty.doClick();

        return root;
    }

    private void buildJsonTree(String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = mapper.readTree(json);
        
        DefaultMutableTreeNode rootTreeNode = convertJsonNodeToTreeNode(rootNode, "", 0, true);
        prettyTree.setModel(new DefaultTreeModel(rootTreeNode));
        
        // 默认全部展开
        for (int i = 0; i < prettyTree.getRowCount(); i++) {
            prettyTree.expandRow(i);
        }
    }

    private DefaultMutableTreeNode convertJsonNodeToTreeNode(JsonNode node, String keyName, int depth, boolean isLast) {
        String keyHtml = keyName.isEmpty() ? "" : "<span style='color:#e06c75'>\"" + keyName + "\"</span>: ";
        String comma = isLast ? "" : "<span style='color:#abb2bf'>,</span>";
        String color = BRACKET_COLORS[depth % BRACKET_COLORS.length];

        if (node.isObject()) {
            String open = "<html>" + keyHtml + "<span style='color:" + color + "'><b>{</b></span></html>";
            String close = "<html>" + keyHtml + "<span style='color:" + color + "'><b>{ ... }</b></span>" + comma + "</html>";
            
            DefaultMutableTreeNode container = new DefaultMutableTreeNode(new JsonFolderNode(open, close));
            
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = node.fields();
            java.util.List<java.util.Map.Entry<String, JsonNode>> list = new java.util.ArrayList<>();
            while (fields.hasNext()) {
                list.add(fields.next());
            }
            
            for (int i = 0; i < list.size(); i++) {
                java.util.Map.Entry<String, JsonNode> field = list.get(i);
                boolean lastField = (i == list.size() - 1);
                container.add(convertJsonNodeToTreeNode(field.getValue(), field.getKey(), depth + 1, lastField));
            }
            
            // 添加右花括号作为代码的结束标记
            String endText = "<html><span style='color:" + color + "'><b>}</b></span>" + comma + "</html>";
            container.add(new DefaultMutableTreeNode(new JsonFolderNode(endText, endText)));
            return container;
            
        } else if (node.isArray()) {
            String open = "<html>" + keyHtml + "<span style='color:" + color + "'><b>[</b></span></html>";
            String close = "<html>" + keyHtml + "<span style='color:" + color + "'><b>[ ... ]</b></span>" + comma + "</html>";
            
            DefaultMutableTreeNode container = new DefaultMutableTreeNode(new JsonFolderNode(open, close));
            
            for (int i = 0; i < node.size(); i++) {
                boolean lastField = (i == node.size() - 1);
                container.add(convertJsonNodeToTreeNode(node.get(i), "", depth + 1, lastField));
            }
            
            // 结束中括号
            String endText = "<html><span style='color:" + color + "'><b>]</b></span>" + comma + "</html>";
            container.add(new DefaultMutableTreeNode(new JsonFolderNode(endText, endText)));
            return container;
        } else {
            // 叶子节点：普通值着色
            String valHtml = "";
            if (node.isTextual()) {
                valHtml = "<span style='color:#98c311'>\"" + escapeHtml(node.asText()) + "\"</span>";
            } else if (node.isNumber()) {
                valHtml = "<span style='color:#d19a66'>" + node.toString() + "</span>";
            } else if (node.isBoolean()) {
                valHtml = "<span style='color:#d19a66'><b>" + node.toString() + "</b></span>";
            } else {
                valHtml = "<span style='color:#abb2bf'>null</span>";
            }
            
            String text = "<html>" + keyHtml + valHtml + comma + "</html>";
            return new DefaultMutableTreeNode(new JsonFolderNode(text, text));
        }
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * 自定义折叠节点载体
     */
    static class JsonFolderNode {
        String openText;
        String closeText;

        JsonFolderNode(String openText, String closeText) {
            this.openText = openText;
            this.closeText = closeText;
        }

        @Override
        public String toString() {
            return openText;
        }
    }

    /**
     * 树行内渲染，切换展开折叠样式
     */
    static class CodeTreeCellRenderer extends DefaultTreeCellRenderer {
        private final JTree tree;

        CodeTreeCellRenderer(JTree tree) {
            this.tree = tree;
            setOpenIcon(null);
            setClosedIcon(null);
            setLeafIcon(null);
            setBackgroundNonSelectionColor(new Color(0, 0, 0, 0));
            setBorderSelectionColor(new Color(0, 0, 0, 0));
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                                                      boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

            if (value instanceof DefaultMutableTreeNode) {
                Object userObj = ((DefaultMutableTreeNode) value).getUserObject();
                if (userObj instanceof JsonFolderNode) {
                    JsonFolderNode node = (JsonFolderNode) userObj;
                    if (expanded) {
                        setText(node.openText);
                    } else {
                        setText(node.closeText);
                    }
                }
            }
            
            // 选中行高亮着色，保持温和的前背景色
            if (sel) {
                setBackground(UIManager.getColor("List.selectionBackground"));
                setForeground(UIManager.getColor("List.selectionForeground"));
            } else {
                setBackground(null);
                setForeground(null);
            }
            return this;
        }
    }
}
