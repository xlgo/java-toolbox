package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.ActionBar;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
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
        JPanel root = Layouts.page();

        // ===== 顶部操作卡片 =====
        // 与 JSON 面板保持同一套骨架：动作条收在 NORTH 的卡片里，
        // 输入与结果占满剩余高度，按钮不再悬空在页面中间。
        JButton pretty = Buttons.primary("美化");
        JButton compact = Buttons.secondary("压缩");
        JButton copy = Buttons.ghost("复制结果");
        JButton clear = Buttons.ghost("清空");

        ActionBar bar = new ActionBar();
        bar.left(Fields.caption("美化输出为可折叠的标签树，压缩输出为单行文本"));
        bar.right(clear);
        bar.right(copy);
        bar.right(compact);
        bar.right(pretty);
        Card config = Card.titled("XML 格式化");
        config.setContent(bar);

        JTextArea input = Fields.area(6, 40);
        input.setText("<application name=\"JavaToolbox\" version=\"1.2.0\">\n  <server port=\"8080\" enableTls=\"false\">\n    <host>localhost</host>\n    <timeout connection=\"5000\" socket=\"30000\" />\n  </server>\n  <modules>\n    <module id=\"bpmn\" active=\"true\">\n      <name>BPMN 2.0 Designer</name>\n      <tags>\n        <tag>workflow</tag>\n        <tag>editor</tag>\n        <tag>xml</tag>\n      </tags>\n    </module>\n    <module id=\"k8s\" active=\"true\">\n      <name>Kubernetes Generator</name>\n      <tags>\n        <tag>yaml</tag>\n        <tag>k8s</tag>\n        <tag>deploy</tag>\n      </tags>\n    </module>\n  </modules>\n  <properties>\n    <property key=\"theme\" value=\"dark\" />\n    <property key=\"maxHistory\" value=\"50\" />\n  </properties>\n</application>");
        
        // 输出区仍用 CardLayout 在折叠树与压缩文本之间切换，
        // 两个视图各自套一张 flush 卡片，标题随视图切换，事件代码无需改动。
        cardLayout = new CardLayout();
        outputCardPanel = new JPanel(cardLayout);
        outputCardPanel.setOpaque(false);

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

        Card treeCard = Card.flush("结果 (点击左侧三角箭头折叠/展开)");
        treeCard.setContent(Fields.scroll(prettyTree));
        outputCardPanel.add(treeCard, "PRETTY");

        // 2. 压缩文本框卡片
        compactPane = new JTextPane();
        compactPane.setEditable(false);
        compactPane.setFont(UIUtils.monoFont());
        Card compactCard = Card.flush("结果 (压缩)");
        compactCard.setContent(Fields.scroll(compactPane));
        outputCardPanel.add(compactCard, "COMPACT");

        Card inputCard = Card.flush("输入 XML");
        inputCard.setContent(Fields.scroll(input));

        // 左输入 / 右结果：XML 行普遍偏长，横向分栏比上下分栏更能利用宽屏
        root.add(config, BorderLayout.NORTH);
        root.add(Layouts.splitHorizontal(inputCard, outputCardPanel, 0.5), BorderLayout.CENTER);

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
