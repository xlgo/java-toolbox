package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
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
 * XML 格式化 / 压缩面板：支持直接在输出结果中进行行内折叠，带属性与标签语法着色。
 */
public class XmlPanel extends ToolPanel {

    private CardLayout cardLayout;
    private JPanel outputCardPanel;

    private JTree prettyTree;        // 美化视图（可折叠代码树）
    private JTextPane compactPane;   // 压缩视图（单行文本）

    private String lastXml = "";

    public XmlPanel() {
        super("format", "xml.format",
                "XML", "美化", "压缩", "格式化",
                "Xml美化", "Xml压缩");
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

        JTextArea input = new JTextArea(6, 40);
        input.setFont(UIUtils.monoFont());
        input.setText("<application name=\"JavaToolbox\" version=\"1.2.0\">\n  <server port=\"8080\" enableTls=\"false\">\n    <host>localhost</host>\n    <timeout connection=\"5000\" socket=\"30000\" />\n  </server>\n  <modules>\n    <module id=\"bpmn\" active=\"true\">\n      <name>BPMN 2.0 Designer</name>\n      <tags>\n        <tag>workflow</tag>\n        <tag>editor</tag>\n        <tag>xml</tag>\n      </tags>\n    </module>\n    <module id=\"k8s\" active=\"true\">\n      <name>Kubernetes Generator</name>\n      <tags>\n        <tag>yaml</tag>\n        <tag>k8s</tag>\n        <tag>deploy</tag>\n      </tags>\n    </module>\n  </modules>\n  <properties>\n    <property key=\"theme\" value=\"dark\" />\n    <property key=\"maxHistory\" value=\"50\" />\n  </properties>\n</application>");
        
        cardLayout = new CardLayout();
        outputCardPanel = new JPanel(cardLayout);

        // 1. 美化可折叠大纲树卡片
        prettyTree = new JTree(new DefaultMutableTreeNode("XML"));
        prettyTree.setFont(UIUtils.monoFont());
        prettyTree.putClientProperty("JTree.lineStyle", "None");
        prettyTree.setRootVisible(true);
        prettyTree.setShowsRootHandles(true);
        prettyTree.setRowHeight(20);
        prettyTree.setCellRenderer(new CodeTreeCellRenderer(prettyTree));

        DefaultTreeCellRenderer renderer = (DefaultTreeCellRenderer) prettyTree.getCellRenderer();
        renderer.setOpenIcon(null);
        renderer.setClosedIcon(null);
        renderer.setLeafIcon(null);

        JScrollPane treeScroll = new JScrollPane(prettyTree);
        treeScroll.setBorder(BorderFactory.createTitledBorder("结果 (点击左侧三角箭头折叠/展开)"));
        outputCardPanel.add(treeScroll, "PRETTY");

        // 2. 压缩文本框卡片
        compactPane = new JTextPane();
        compactPane.setEditable(false);
        compactPane.setFont(UIUtils.monoFont());
        JScrollPane textScroll = new JScrollPane(compactPane);
        textScroll.setBorder(BorderFactory.createTitledBorder("结果 (压缩)"));
        outputCardPanel.add(textScroll, "COMPACT");

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                UIUtils.scrollText(input, "输入 XML"),
                outputCardPanel);
        split.setResizeWeight(0.35);
        root.add(split, BorderLayout.CENTER);

        pretty.addActionListener(e -> {
            String xmlText = input.getText().trim();
            if (xmlText.isEmpty()) return;
            try {
                buildXmlTree(xmlText);
                lastXml = format(xmlText, true);
                cardLayout.show(outputCardPanel, "PRETTY");
            } catch (Exception ex) {
                UIUtils.error(root, "XML 解析出错：" + ex.getMessage());
            }
        });

        compact.addActionListener(e -> {
            String xmlText = input.getText().trim();
            if (xmlText.isEmpty()) return;
            try {
                lastXml = format(xmlText, false);
                compactPane.setText(lastXml);
                cardLayout.show(outputCardPanel, "COMPACT");
            } catch (Exception ex) {
                UIUtils.error(root, "XML 解析出错：" + ex.getMessage());
            }
        });

        copy.addActionListener(e -> {
            if (!lastXml.isEmpty()) {
                UIUtils.copyToClipboard(lastXml);
            }
        });

        clear.addActionListener(e -> {
            input.setText("");
            compactPane.setText("");
            lastXml = "";
            prettyTree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("XML")));
        });

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

    private void buildXmlTree(String xml) throws Exception {
        InputSource src = new InputSource(new StringReader(xml.trim()));
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(src);
        org.w3c.dom.Element rootElement = doc.getDocumentElement();
        
        DefaultMutableTreeNode rootTreeNode = convertDomNodeToTreeNode(rootElement);
        prettyTree.setModel(new DefaultTreeModel(rootTreeNode));
        
        // 默认展开
        for (int i = 0; i < prettyTree.getRowCount(); i++) {
            prettyTree.expandRow(i);
        }
    }

    private DefaultMutableTreeNode convertDomNodeToTreeNode(org.w3c.dom.Node node) {
        String nodeName = node.getNodeName();
        StringBuilder attrsHtml = new StringBuilder();
        if (node.hasAttributes()) {
            org.w3c.dom.NamedNodeMap attrs = node.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                org.w3c.dom.Node attr = attrs.item(i);
                attrsHtml.append(" <span style='color:#d19a66'>")
                         .append(attr.getNodeName())
                         .append("</span>=<span style='color:#98c311'>\"")
                         .append(escapeHtml(attr.getNodeValue()))
                         .append("\"</span>");
            }
        }

        // 标签开闭文本构建
        String open = "<html><span style='color:#e06c75'><b>&lt;" + nodeName + "</b></span>" + attrsHtml + "<span style='color:#e06c75'><b>&gt;</b></span></html>";
        String close = "<html><span style='color:#e06c75'><b>&lt;" + nodeName + "</b></span>" + attrsHtml + "<span style='color:#e06c75'><b>&gt;...&lt;/" + nodeName + "&gt;</b></span></html>";

        org.w3c.dom.NodeList children = node.getChildNodes();
        
        // 检查是否只包含一个纯文本子节点
        if (children.getLength() == 1 && children.item(0).getNodeType() == org.w3c.dom.Node.TEXT_NODE) {
            String textContent = escapeHtml(children.item(0).getTextContent().trim());
            String leafText = "<html><span style='color:#e06c75'><b>&lt;" + nodeName + "</b></span>" + attrsHtml + "<span style='color:#e06c75'><b>&gt;</b></span>"
                    + textContent + "<span style='color:#e06c75'><b>&lt;/" + nodeName + "&gt;</b></span></html>";
            return new DefaultMutableTreeNode(new XmlFolderNode(leafText, leafText));
        }

        DefaultMutableTreeNode container = new DefaultMutableTreeNode(new XmlFolderNode(open, close));
        
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node child = children.item(i);
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                container.add(convertDomNodeToTreeNode(child));
            }
        }

        // 结束标签作为代码行塞入
        String endText = "<html><span style='color:#e06c75'><b>&lt;/" + nodeName + "&gt;</b></span></html>";
        container.add(new DefaultMutableTreeNode(new XmlFolderNode(endText, endText)));
        return container;
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    static class XmlFolderNode {
        String openText;
        String closeText;

        XmlFolderNode(String openText, String closeText) {
            this.openText = openText;
            this.closeText = closeText;
        }

        @Override
        public String toString() {
            return openText;
        }
    }

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
                if (userObj instanceof XmlFolderNode) {
                    XmlFolderNode node = (XmlFolderNode) userObj;
                    if (expanded) {
                        setText(node.openText);
                    } else {
                        setText(node.closeText);
                    }
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
    }
}
