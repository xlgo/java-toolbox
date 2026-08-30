package com.aqishi.toolbox.feature.codec.ui;

import com.aqishi.toolbox.feature.codec.domain.JsonFormatter;
import com.aqishi.toolbox.ui.kit.ActionBar;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.util.Json;
import com.aqishi.toolbox.util.UIUtils;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * JSON 格式化 / 压缩面板：支持直接在生成结果中进行行内折叠，带彩虹括号和语法高亮。
 */
public class JsonPanel extends AbstractTreeFormatPanel {

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
    protected String configTitle() {
        return "JSON 格式化与代码生成";
    }

    @Override
    protected String caption() {
        return "美化输出为可折叠的代码树，压缩输出为单行文本";
    }

    @Override
    protected String inputCardTitle() {
        return "输入 JSON";
    }

    @Override
    protected String treeRootLabel() {
        return "JSON";
    }

    @Override
    protected String parseErrorPrefix() {
        return "JSON 解析出错：";
    }

    @Override
    protected String defaultInput() {
        return "{\n  \"projectName\": \"JavaToolbox\",\n  \"version\": \"1.2.0\",\n  \"active\": true,\n  \"server\": {\n    \"port\": 8080,\n    \"host\": \"localhost\",\n    \"enableTls\": false,\n    \"sslConfig\": null\n  },\n  \"modules\": [\n    {\n      \"id\": \"bpmn\",\n      \"name\": \"BPMN 2.0 Designer\",\n      \"tags\": [\"workflow\", \"editor\", \"xml\"]\n    },\n    {\n      \"id\": \"k8s\",\n      \"name\": \"Kubernetes Generator\",\n      \"tags\": [\"yaml\", \"k8s\", \"deploy\"]\n    }\n  ],\n  \"systemMetrics\": {\n    \"cpu\": {\n      \"cores\": 8,\n      \"loadPercent\": 24.5\n    },\n    \"memory\": {\n      \"totalGb\": 16,\n      \"usedGb\": 6.2\n    }\n  }\n}";
    }

    @Override
    protected void addExtraActions(ActionBar bar) {
        JButton genJavaBtn = Buttons.secondary("转 Java POJO");
        genJavaBtn.addActionListener(e -> generateCode("JAVA"));
        JButton genTsBtn = Buttons.secondary("转 TypeScript");
        genTsBtn.addActionListener(e -> generateCode("TS"));
        bar.right(genTsBtn);
        bar.right(genJavaBtn);
    }

    @Override
    protected void buildTree(String json) throws java.io.IOException {
        JsonNode rootNode = Json.mapper().readTree(json);

        DefaultMutableTreeNode rootTreeNode = convertJsonNodeToTreeNode(rootNode, "", 0, true);
        prettyTree.setModel(new DefaultTreeModel(rootTreeNode));

        // 默认全部展开
        for (int i = 0; i < prettyTree.getRowCount(); i++) {
            prettyTree.expandRow(i);
        }
    }

    @Override
    protected String prettyText(String json) throws java.io.IOException {
        return JsonFormatter.pretty(json);
    }

    @Override
    protected String compactText(String json) throws java.io.IOException {
        return JsonFormatter.compact(json);
    }

    private DefaultMutableTreeNode convertJsonNodeToTreeNode(JsonNode node, String keyName, int depth, boolean isLast) {
        String keyHtml = keyName.isEmpty() ? "" : "<span style='color:#e06c75'>\"" + keyName + "\"</span>: ";
        String comma = isLast ? "" : "<span style='color:#abb2bf'>,</span>";
        String color = BRACKET_COLORS[depth % BRACKET_COLORS.length];

        if (node.isObject()) {
            String open = "<html>" + keyHtml + "<span style='color:" + color + "'><b>{</b></span></html>";
            String close = "<html>" + keyHtml + "<span style='color:" + color + "'><b>{ ... }</b></span>" + comma + "</html>";

            DefaultMutableTreeNode container = new DefaultMutableTreeNode(new CodeFolderNode(open, close));

            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            List<Map.Entry<String, JsonNode>> list = new ArrayList<>();
            while (fields.hasNext()) {
                list.add(fields.next());
            }

            for (int i = 0; i < list.size(); i++) {
                Map.Entry<String, JsonNode> field = list.get(i);
                boolean lastField = (i == list.size() - 1);
                container.add(convertJsonNodeToTreeNode(field.getValue(), field.getKey(), depth + 1, lastField));
            }

            // 添加右花括号作为代码的结束标记
            String endText = "<html><span style='color:" + color + "'><b>}</b></span>" + comma + "</html>";
            container.add(new DefaultMutableTreeNode(new CodeFolderNode(endText, endText)));
            return container;

        } else if (node.isArray()) {
            String open = "<html>" + keyHtml + "<span style='color:" + color + "'><b>[</b></span></html>";
            String close = "<html>" + keyHtml + "<span style='color:" + color + "'><b>[ ... ]</b></span>" + comma + "</html>";

            DefaultMutableTreeNode container = new DefaultMutableTreeNode(new CodeFolderNode(open, close));

            for (int i = 0; i < node.size(); i++) {
                boolean lastField = (i == node.size() - 1);
                container.add(convertJsonNodeToTreeNode(node.get(i), "", depth + 1, lastField));
            }

            // 结束中括号
            String endText = "<html><span style='color:" + color + "'><b>]</b></span>" + comma + "</html>";
            container.add(new DefaultMutableTreeNode(new CodeFolderNode(endText, endText)));
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
            return new DefaultMutableTreeNode(new CodeFolderNode(text, text));
        }
    }

    private void generateCode(String lang) {
        String jsonText = inputArea.getText().trim();
        if (jsonText.isEmpty()) {
            UIUtils.error(getView(), "请在输入框中输入有效的 JSON 内容！");
            return;
        }

        try {
            JsonNode rootNode = Json.mapper().readTree(jsonText);

            StringBuilder codeSb = new StringBuilder();
            if ("JAVA".equalsIgnoreCase(lang)) {
                buildJavaClass(rootNode, "RootDto", codeSb);
            } else {
                buildTsInterface(rootNode, "RootDto", codeSb);
            }

            JTextArea area = new JTextArea(codeSb.toString(), 18, 60);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JScrollPane scrollPane = new JScrollPane(area);

            JPanel dialogPanel = new JPanel(new BorderLayout(0, 8));
            dialogPanel.add(new JLabel("根据 JSON 结构自动生成的 " + lang + " 实体类型声明:"), BorderLayout.NORTH);
            dialogPanel.add(scrollPane, BorderLayout.CENTER);

            JButton copyBtn = new JButton("一键复制生成的代码");
            copyBtn.addActionListener(e -> {
                UIUtils.copyToClipboard(area.getText());
                UIUtils.info(getView(), "生成代码已复制到剪贴板！");
            });
            dialogPanel.add(copyBtn, BorderLayout.SOUTH);

            UIUtils.dialog(getView(), dialogPanel, "代码生成器 (" + lang + ")");

        } catch (Exception ex) {
            UIUtils.error(getView(), "解析失败: " + ex.getMessage());
        }
    }

    private void buildJavaClass(JsonNode node, String className, StringBuilder sb) {
        if (!node.isObject()) return;

        sb.append("public class ").append(className).append(" {\n");
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        List<String> gettersAndSetters = new ArrayList<>();

        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            JsonNode val = field.getValue();

            String type = getJavaType(val, capitalize(name));
            sb.append("    private ").append(type).append(" ").append(name).append(";\n");

            String capName = capitalize(name);
            gettersAndSetters.add("    public " + type + " get" + capName + "() { return " + name + "; }\n" +
                    "    public void set" + capName + "(" + type + " " + name + ") { this." + name + " = " + name + "; }\n");
        }

        sb.append("\n");
        for (String gs : gettersAndSetters) {
            sb.append(gs);
        }
        sb.append("}\n\n");
    }

    private String getJavaType(JsonNode node, String fieldNameCap) {
        if (node.isTextual()) return "String";
        if (node.isInt() || node.isLong()) return "Integer";
        if (node.isFloat() || node.isDouble()) return "Double";
        if (node.isBoolean()) return "Boolean";
        if (node.isArray()) {
            if (node.size() > 0) {
                return "List<" + getJavaType(node.get(0), fieldNameCap + "Item") + ">";
            }
            return "List<Object>";
        }
        if (node.isObject()) return fieldNameCap + "Dto";
        return "Object";
    }

    private void buildTsInterface(JsonNode node, String interfaceName, StringBuilder sb) {
        if (!node.isObject()) return;

        sb.append("export interface ").append(interfaceName).append(" {\n");
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();

        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();

            String type = getTsType(field.getValue(), capitalize(name));
            sb.append("  ").append(name).append("?: ").append(type).append(";\n");
        }
        sb.append("}\n\n");
    }

    private String getTsType(JsonNode node, String fieldNameCap) {
        if (node.isTextual()) return "string";
        if (node.isNumber()) return "number";
        if (node.isBoolean()) return "boolean";
        if (node.isArray()) {
            if (node.size() > 0) {
                return getTsType(node.get(0), fieldNameCap + "Item") + "[]";
            }
            return "any[]";
        }
        if (node.isObject()) return fieldNameCap;
        return "any";
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }
}
