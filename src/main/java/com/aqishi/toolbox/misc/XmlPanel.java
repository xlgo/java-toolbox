package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.swing.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.*;
import java.io.StringReader;
import java.io.StringWriter;

/**
 * XML 格式化 / 压缩面板。
 */
public class XmlPanel extends ToolPanel {

    public XmlPanel() {
        super("杂项", "XML 格式化");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton pretty = UIUtils.button("美化", 80);
        JButton compact = UIUtils.button("压缩", 80);
        JButton copy = UIUtils.button("复制结果", 100);
        JButton clear = UIUtils.button("清空", 80);
        btns.add(pretty); btns.add(compact); btns.add(copy); btns.add(clear);
        root.add(btns, BorderLayout.NORTH);

        JTextArea input = new JTextArea(8, 40);
        input.setFont(UIUtils.monoFont());
        input.setText("<note><to>User</to><from>Antigravity</from><heading>Reminder</heading><body>XML formatter is ready!</body></note>");
        JTextArea out = new JTextArea(10, 40);
        out.setFont(UIUtils.monoFont());
        out.setEditable(false);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                UIUtils.scrollText(input, "输入 XML"),
                UIUtils.scrollText(out, "输出"));
        split.setResizeWeight(0.4);
        root.add(split, BorderLayout.CENTER);

        pretty.addActionListener(e -> {
            try {
                out.setText(format(input.getText(), true));
            } catch (Exception ex) {
                out.setText("解析 XML 出错：\n" + ex.getMessage());
            }
        });
        compact.addActionListener(e -> {
            try {
                out.setText(format(input.getText(), false));
            } catch (Exception ex) {
                out.setText("解析 XML 出错：\n" + ex.getMessage());
            }
        });
        copy.addActionListener(e -> UIUtils.copyToClipboard(out.getText()));
        clear.addActionListener(e -> { input.setText(""); out.setText(""); });
        
        pretty.doClick();

        return root;
    }

    private String format(String xml, boolean pretty) throws Exception {
        if (xml == null || xml.trim().isEmpty()) return "";
        
        InputSource src = new InputSource(new StringReader(xml.trim()));
        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(src);

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        
        if (pretty) {
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        } else {
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
        }
        
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        String result = writer.toString();
        
        if (!pretty) {
            result = result.replaceAll(">\\s+<", "><").trim();
        }
        return result;
    }
}
