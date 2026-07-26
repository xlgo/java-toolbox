package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.ActionBar;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
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
        JPanel root = Layouts.page();

        // ===== 顶部操作卡片 =====
        // 四个动作都作用于整个页面，收进一张卡的操作条里；
        // 放 NORTH 而不是 CENTER，下面的输入 / 结果才能吃掉全部剩余高度。
        JButton pretty = Buttons.primary("美化");
        JButton compact = Buttons.secondary("压缩");
        JButton copy = Buttons.ghost("复制结果");
        JButton clear = Buttons.ghost("清空");

        ActionBar bar = new ActionBar();
        bar.left(Fields.caption("美化输出为可折叠的代码树，压缩输出为单行文本"));
        bar.right(clear);
        bar.right(copy);
        bar.right(compact);
        bar.right(pretty);
        Card config = Card.titled("JSON 格式化");
        config.setContent(bar);

        // 输入区域
        JTextArea input = Fields.area(6, 40);
        input.setText("{\n  \"projectName\": \"JavaToolbox\",\n  \"version\": \"1.2.0\",\n  \"active\": true,\n  \"server\": {\n    \"port\": 8080,\n    \"host\": \"localhost\",\n    \"enableTls\": false,\n    \"sslConfig\": null\n  },\n  \"modules\": [\n    {\n      \"id\": \"bpmn\",\n      \"name\": \"BPMN 2.0 Designer\",\n      \"tags\": [\"workflow\", \"editor\", \"xml\"]\n    },\n    {\n      \"id\": \"k8s\",\n      \"name\": \"Kubernetes Generator\",\n      \"tags\": [\"yaml\", \"k8s\", \"deploy\"]\n    }\n  ],\n  \"systemMetrics\": {\n    \"cpu\": {\n      \"cores\": 8,\n      \"loadPercent\": 24.5\n    },\n    \"memory\": {\n      \"totalGb\": 16,\n      \"usedGb\": 6.2\n    }\n  }\n}");

        // 输出区域：使用 CardLayout 来切换折叠树和普通单行文本。
        // 两个视图各自套一张 flush 卡片，切换 card 时标题也跟着换，
        // 因此事件代码不需要额外维护标题文案。
        cardLayout = new CardLayout();
        outputCardPanel = new JPanel(cardLayout);
        outputCardPanel.setOpaque(false);

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

        Card treeCard = Card.flush("结果 (点击左侧三角箭头折叠/展开)");
        treeCard.setContent(Fields.scroll(prettyTree));
        outputCardPanel.add(treeCard, "PRETTY");

        // 2. 压缩视图卡片
        compactPane = new JTextPane();
        compactPane.setEditable(false);
        compactPane.setFont(UIUtils.monoFont());
        Card compactCard = Card.flush("结果 (压缩)");
        compactCard.setContent(Fields.scroll(compactPane));
        outputCardPanel.add(compactCard, "COMPACT");

        Card inputCard = Card.flush("输入 JSON");
        inputCard.setContent(Fields.scroll(input));

        // 左输入 / 右结果：横向分栏后拖窗口时两侧同时变宽，
        // 长的 JSON 行与深层嵌套的树节点都少一次横向滚动。
        root.add(config, BorderLayout.NORTH);
        root.add(Layouts.splitHorizontal(inputCard, outputCardPanel, 0.5), BorderLayout.CENTER);

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
