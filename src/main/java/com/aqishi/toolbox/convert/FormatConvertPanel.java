package com.aqishi.toolbox.convert;

import com.aqishi.toolbox.ui.ToolPanel;
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
        super("转换", "格式转换");
        
        this.jsonMapper = new ObjectMapper();
        this.jsonMapper.enable(SerializationFeature.INDENT_OUTPUT);
        
        this.yamlMapper = new YAMLMapper();
        this.xmlMapper = new XmlMapper();
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ===== 顶部控制栏 =====
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        
        JLabel fromLabel = new JLabel("源格式:");
        fromLabel.setFont(UIUtils.plainFont());
        JComboBox<String> fromCombo = new JComboBox<>(new String[]{"JSON", "XML", "YAML", "CSV", "Properties"});
        fromCombo.setFont(UIUtils.plainFont());
        
        JLabel toLabel = new JLabel(" 目标格式:");
        toLabel.setFont(UIUtils.plainFont());
        JComboBox<String> toCombo = new JComboBox<>(new String[]{"YAML", "JSON", "XML", "CSV", "Properties"});
        toCombo.setFont(UIUtils.plainFont());
        
        JButton btn = UIUtils.button("转换", 80);
        JButton copy = UIUtils.button("复制结果", 100);
        JButton clear = UIUtils.button("清空", 80);

        top.add(fromLabel);
        top.add(fromCombo);
        top.add(toLabel);
        top.add(toCombo);
        top.add(btn);
        top.add(copy);
        top.add(clear);
        
        root.add(top, BorderLayout.NORTH);

        // ===== 中间输入输出 =====
        JTextArea input = new JTextArea(8, 40);
        input.setFont(UIUtils.monoFont());
        input.setText("{\n  \"name\": \"java-toolbox\",\n  \"version\": \"1.2.0\",\n  \"author\": {\n    \"name\": \"aqishi\"\n  }\n}");
        
        JTextArea out = new JTextArea(10, 40);
        out.setFont(UIUtils.monoFont());
        out.setEditable(false);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                UIUtils.scrollText(input, "输入"),
                UIUtils.scrollText(out, "输出"));
        split.setResizeWeight(0.4);
        root.add(split, BorderLayout.CENTER);

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
