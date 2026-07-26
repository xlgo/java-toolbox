package com.aqishi.toolbox.convert;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.util.UIUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * JSON, XML, YAML, CSV 格式互相转换面板。
 */
public class FormatConvertPanel extends ToolPanel {

    private final ObjectMapper jsonMapper;
    private final YAMLMapper yamlMapper;
    private final XmlMapper xmlMapper;

    public FormatConvertPanel() {
        super("convert", "format.convert",
                "JSON", "XML", "YAML", "CSV", "Properties",
                "格式互转", "数据转换", "序列化");
        
        this.jsonMapper = new ObjectMapper();
        this.jsonMapper.enable(SerializationFeature.INDENT_OUTPUT);
        
        this.yamlMapper = new YAMLMapper();
        this.xmlMapper = new XmlMapper();
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ===== 顶部配置卡片：源格式 / 目标格式 + 主操作 =====
        // 两个下拉框用 FormGrid 排成两行，标签右对齐、控件左边缘对齐；
        // 下拉项都是短词，用固定宽度并外套一层左对齐容器，避免被网格拉满整行。
        JComboBox<String> fromCombo = Fields.combo(
                new String[]{"JSON", "XML", "YAML", "CSV", "Properties"}, 160);
        JComboBox<String> toCombo = Fields.combo(
                new String[]{"YAML", "JSON", "XML", "CSV", "Properties"}, 160);

        JButton btn = Buttons.primary("转换");
        JButton copy = Buttons.ghost("复制结果");
        JButton clear = Buttons.ghost("清空");

        FormGrid form = new FormGrid();
        form.row("源格式:", keepWidth(fromCombo));
        form.row("目标格式:", keepWidth(toCombo));

        Card config = Card.titled("格式互转");
        config.setContent(form);
        config.addHeaderAction(clear);
        config.addHeaderAction(btn);

        // ===== 输入 / 输出左右并排 =====
        // 改成横向分栏后，窗口拉宽时两边的等宽文本同时变宽，长行不用再靠横向滚动。
        JTextArea input = Fields.area(8, 40);
        input.setText("{\n  \"name\": \"java-toolbox\",\n  \"version\": \"1.2.0\",\n  \"author\": {\n    \"name\": \"aqishi\"\n  }\n}");

        JTextArea out = Fields.output(10, 40);

        Card inputCard = Card.flush("输入");
        inputCard.setContent(Fields.scroll(input));

        Card outputCard = Card.flush("输出");
        outputCard.setContent(Fields.scroll(out));
        outputCard.addHeaderAction(copy);

        root.add(config, BorderLayout.NORTH);
        root.add(Layouts.splitHorizontal(inputCard, outputCard, 0.5), BorderLayout.CENTER);

        btn.addActionListener(e -> {
            try {
                String srcFormat = (String) fromCombo.getSelectedItem();
                String destFormat = (String) toCombo.getSelectedItem();
                String raw = input.getText().trim();
                if (raw.isEmpty()) {
                    out.setText("");
                    return;
                }
                
                Object obj = parseSource(raw, srcFormat);
                String result = formatDest(obj, destFormat);
                out.setText(result);
            } catch (Exception ex) {
                out.setText("转换失败:\n" + ex.getMessage());
            }
        });

        copy.addActionListener(e -> UIUtils.copyToClipboard(out.getText()));
        clear.addActionListener(e -> { input.setText(""); out.setText(""); });

        // 默认转换一次
        btn.doClick();

        return root;
    }

    /** 把固定宽度控件包一层左对齐容器，抵消 FormGrid 输入列的水平填充 */
    private static JComponent keepWidth(JComponent field) {
        JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        wrapper.setOpaque(false);
        wrapper.add(field);
        return wrapper;
    }

    private Object parseSource(String text, String format) throws Exception {
        switch (format) {
            case "JSON":
                return jsonMapper.readValue(text, Object.class);
            case "YAML":
                return yamlMapper.readValue(text, Object.class);
            case "XML":
                return xmlMapper.readValue(text, Object.class);
            case "CSV":
                return parseCsv(text);
            case "Properties":
                return parseProperties(text);
            default:
                throw new IllegalArgumentException("不支持的源格式: " + format);
        }
    }

    private String formatDest(Object obj, String format) throws Exception {
        switch (format) {
            case "JSON":
                return jsonMapper.writeValueAsString(obj);
            case "YAML":
                return yamlMapper.writeValueAsString(obj);
            case "XML":
                return xmlMapper.writeValueAsString(obj);
            case "CSV":
                return toCsv(obj);
            case "Properties":
                return toProperties(obj);
            default:
                throw new IllegalArgumentException("不支持的目标格式: " + format);
        }
    }

    private Object parseProperties(String text) throws Exception {
        Properties props = new Properties();
        props.load(new java.io.StringReader(text));
        return unflattenProperties(props);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unflattenProperties(Properties props) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            String val = props.getProperty(name);
            String[] keys = name.split("\\.");
            Map<String, Object> current = root;
            for (int i = 0; i < keys.length - 1; i++) {
                String key = keys[i];
                Object next = current.get(key);
                if (next instanceof Map) {
                    current = (Map<String, Object>) next;
                } else {
                    Map<String, Object> newMap = new LinkedHashMap<>();
                    current.put(key, newMap);
                    current = newMap;
                }
            }
            current.put(keys[keys.length - 1], val);
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private String toProperties(Object obj) throws Exception {
        Properties props = new Properties();
        if (obj instanceof Map) {
            flattenMap((Map<String, Object>) obj, "", props);
        } else if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item instanceof Map) {
                    flattenMap((Map<String, Object>) item, String.valueOf(i), props);
                } else {
                    props.setProperty(String.valueOf(i), item == null ? "" : item.toString());
                }
            }
        } else {
            props.setProperty("value", obj == null ? "" : obj.toString());
        }

        java.io.StringWriter writer = new java.io.StringWriter();
        props.store(writer, null);

        String raw = writer.toString();
        String[] lines = raw.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("#")) continue;
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private void flattenMap(Map<String, Object> map, String prefix, Properties props) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map) {
                flattenMap((Map<String, Object>) entry.getValue(), key, props);
            } else if (entry.getValue() instanceof List) {
                List<?> list = (List<?>) entry.getValue();
                for (int i = 0; i < list.size(); i++) {
                    Object val = list.get(i);
                    if (val instanceof Map) {
                        flattenMap((Map<String, Object>) val, key + "." + i, props);
                    } else {
                        props.setProperty(key + "." + i, val == null ? "" : val.toString());
                    }
                }
            } else {
                props.setProperty(key, entry.getValue() == null ? "" : entry.getValue().toString());
            }
        }
    }

    private List<Map<String, Object>> parseCsv(String csvText) {
        List<Map<String, Object>> result = new ArrayList<>();
        String[] lines = csvText.split("\n");
        if (lines.length == 0) return result;

        List<String> headers = parseCsvLine(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            List<String> values = parseCsvLine(line);
            Map<String, Object> map = new LinkedHashMap<>();
            for (int j = 0; j < headers.size(); j++) {
                String val = j < values.size() ? values.get(j) : "";
                map.put(headers.get(j), val);
            }
            result.add(map);
        }
        return result;
    }

    private List<String> parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                result.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        result.add(sb.toString().trim());
        return result;
    }

    @SuppressWarnings("unchecked")
    private String toCsv(Object obj) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (obj instanceof List) {
            for (Object item : (List<?>) obj) {
                if (item instanceof Map) {
                    list.add((Map<String, Object>) item);
                }
            }
        } else if (obj instanceof Map) {
            list.add((Map<String, Object>) obj);
        }

        if (list.isEmpty()) return "";

        Set<String> headers = new LinkedHashSet<>();
        for (Map<String, Object> map : list) {
            headers.addAll(map.keySet());
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", headers)).append("\n");
        for (Map<String, Object> map : list) {
            List<String> row = new ArrayList<>();
            for (String header : headers) {
                Object val = map.get(header);
                String valStr = val == null ? "" : val.toString();
                if (valStr.contains(",") || valStr.contains("\"") || valStr.contains("\n")) {
                    valStr = "\"" + valStr.replace("\"", "\"\"") + "\"";
                }
                row.add(valStr);
            }
            sb.append(String.join(",", row)).append("\n");
        }
        return sb.toString();
    }
}
