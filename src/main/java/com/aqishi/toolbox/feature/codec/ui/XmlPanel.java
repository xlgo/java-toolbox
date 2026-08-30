package com.aqishi.toolbox.feature.codec.ui;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;

/**
 * XML 格式化 / 压缩面板：支持直接在输出结果中进行行内折叠，带属性与标签语法着色。
 */
public class XmlPanel extends AbstractTreeFormatPanel {

    public XmlPanel() {
        super("format", "xml.format",
                "XML", "美化", "压缩", "格式化",
                "Xml美化", "Xml压缩");
    }

    @Override
    protected String configTitle() {
        return "XML 格式化";
    }

    @Override
    protected String caption() {
        return "美化输出为可折叠的标签树，压缩输出为单行文本";
    }

    @Override
    protected String inputCardTitle() {
        return "输入 XML";
    }

    @Override
    protected String treeRootLabel() {
        return "XML";
    }

    @Override
    protected String parseErrorPrefix() {
        return "XML 解析出错：";
    }

    @Override
    protected String defaultInput() {
        return "<application name=\"JavaToolbox\" version=\"1.2.0\">\n  <server port=\"8080\" enableTls=\"false\">\n    <host>localhost</host>\n    <timeout connection=\"5000\" socket=\"30000\" />\n  </server>\n  <modules>\n    <module id=\"bpmn\" active=\"true\">\n      <name>BPMN 2.0 Designer</name>\n      <tags>\n        <tag>workflow</tag>\n        <tag>editor</tag>\n        <tag>xml</tag>\n      </tags>\n    </module>\n    <module id=\"k8s\" active=\"true\">\n      <name>Kubernetes Generator</name>\n      <tags>\n        <tag>yaml</tag>\n        <tag>k8s</tag>\n        <tag>deploy</tag>\n      </tags>\n    </module>\n  </modules>\n  <properties>\n    <property key=\"theme\" value=\"dark\" />\n    <property key=\"maxHistory\" value=\"50\" />\n  </properties>\n</application>";
    }

    @Override
    protected void buildTree(String xml) throws Exception {
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

    @Override
    protected String prettyText(String xml) throws Exception {
        return format(xml, true);
    }

    @Override
    protected String compactText(String xml) throws Exception {
        return format(xml, false);
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
            return new DefaultMutableTreeNode(new CodeFolderNode(leafText, leafText));
        }

        DefaultMutableTreeNode container = new DefaultMutableTreeNode(new CodeFolderNode(open, close));

        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node child = children.item(i);
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                container.add(convertDomNodeToTreeNode(child));
            }
        }

        // 结束标签作为代码行塞入
        String endText = "<html><span style='color:#e06c75'><b>&lt;/" + nodeName + "&gt;</b></span></html>";
        container.add(new DefaultMutableTreeNode(new CodeFolderNode(endText, endText)));
        return container;
    }
}
