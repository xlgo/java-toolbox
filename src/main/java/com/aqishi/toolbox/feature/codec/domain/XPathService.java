package com.aqishi.toolbox.feature.codec.domain;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * XPath 查询与 XSLT 转换的领域服务。
 *
 * <p>解析一律关闭 DTD 与外部实体：这个工具会被用来打开来路不明的 XML，
 * 外部实体解析等于把本地文件和内网地址暴露给样本作者。</p>
 *
 * <p>本类不产出面向用户的文案，错误信息原样带回解析器的描述，
 * 由界面层决定如何本地化与呈现。</p>
 */
public final class XPathService {

    /** 命名空间感知模式下，为默认命名空间（无前缀）自动分配的前缀。 */
    public static final String DEFAULT_NS_PREFIX = "ns";

    public static final String SAMPLE_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<orders currency=\"CNY\">\n"
                    + "  <order id=\"1001\" status=\"paid\">\n"
                    + "    <customer vip=\"true\">Alice</customer>\n"
                    + "    <items>\n"
                    + "      <item sku=\"A-01\" price=\"199.00\" qty=\"2\">Keyboard</item>\n"
                    + "      <item sku=\"A-07\" price=\"59.90\" qty=\"1\">Mouse</item>\n"
                    + "    </items>\n"
                    + "  </order>\n"
                    + "  <order id=\"1002\" status=\"pending\">\n"
                    + "    <customer vip=\"false\">Bob</customer>\n"
                    + "    <items>\n"
                    + "      <item sku=\"B-11\" price=\"1299.00\" qty=\"1\">Monitor</item>\n"
                    + "    </items>\n"
                    + "  </order>\n"
                    + "</orders>\n";

    public static final String SAMPLE_XSLT =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<xsl:stylesheet version=\"1.0\"\n"
                    + "    xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\">\n"
                    + "  <xsl:output method=\"text\" encoding=\"UTF-8\"/>\n"
                    + "  <xsl:template match=\"/\">\n"
                    + "    <xsl:for-each select=\"orders/order\">\n"
                    + "      <xsl:value-of select=\"@id\"/>\n"
                    + "      <xsl:text> | </xsl:text>\n"
                    + "      <xsl:value-of select=\"customer\"/>\n"
                    + "      <xsl:text> | </xsl:text>\n"
                    + "      <xsl:value-of select=\"sum(items/item/@price)\"/>\n"
                    + "      <xsl:text>&#10;</xsl:text>\n"
                    + "    </xsl:for-each>\n"
                    + "  </xsl:template>\n"
                    + "</xsl:stylesheet>\n";

    /**
     * 把语法错误交回调用方，而不是让默认处理器把堆栈打到 stderr——
     * 桌面应用通常根本没有控制台，用户看到的应该是结果区里的那条说明。
     */
    private static final org.xml.sax.ErrorHandler SILENT_SAX_HANDLER = new org.xml.sax.ErrorHandler() {
        @Override
        public void warning(org.xml.sax.SAXParseException exception) {
        }

        @Override
        public void error(org.xml.sax.SAXParseException exception) throws org.xml.sax.SAXException {
            throw exception;
        }

        @Override
        public void fatalError(org.xml.sax.SAXParseException exception) throws org.xml.sax.SAXException {
            throw exception;
        }
    };

    /** 同上：XSLT 编译期的错误也不往 stderr 打。 */
    private static final javax.xml.transform.ErrorListener SILENT_TRANSFORM_LISTENER =
            new javax.xml.transform.ErrorListener() {
                @Override
                public void warning(javax.xml.transform.TransformerException exception) {
                }

                @Override
                public void error(javax.xml.transform.TransformerException exception)
                        throws javax.xml.transform.TransformerException {
                    throw exception;
                }

                @Override
                public void fatalError(javax.xml.transform.TransformerException exception)
                        throws javax.xml.transform.TransformerException {
                    throw exception;
                }
            };

    /** 结果类型。{@link #AUTO} 先按节点集求值，失败后退化为字符串。 */
    public enum ResultMode {
        AUTO, NODESET, STRING, NUMBER, BOOLEAN
    }

    private static final Map<String, String> COMMON_EXPRESSIONS;

    static {
        Map<String, String> expressions = new LinkedHashMap<>();
        expressions.put("/orders/order", "xpath.common.children");
        expressions.put("//item", "xpath.common.descendant");
        expressions.put("//order[@status='paid']", "xpath.common.attrFilter");
        expressions.put("//item[@price>100]/@sku", "xpath.common.numericFilter");
        expressions.put("//order[1]/customer/text()", "xpath.common.text");
        expressions.put("count(//item)", "xpath.common.count");
        expressions.put("sum(//item/@price)", "xpath.common.sum");
        expressions.put("//item[last()]", "xpath.common.last");
        expressions.put("//*[local-name()='item']", "xpath.common.localName");
        expressions.put("//order[items/item[@qty>1]]", "xpath.common.nested");
        COMMON_EXPRESSIONS = Collections.unmodifiableMap(expressions);
    }

    /** 常用表达式 -> 说明文案的资源键。 */
    public static Map<String, String> commonExpressions() {
        return COMMON_EXPRESSIONS;
    }

    /** XPath 求值结果。成功时 {@link #getOutput()} 已按结果类型序列化。 */
    public static final class QueryResult {
        private final boolean success;
        private final String output;
        private final String errorMessage;
        private final int matchCount;
        private final long elapsedMs;
        private final ResultMode resolvedMode;

        private QueryResult(boolean success, String output, String errorMessage,
                            int matchCount, long elapsedMs, ResultMode resolvedMode) {
            this.success = success;
            this.output = output;
            this.errorMessage = errorMessage;
            this.matchCount = matchCount;
            this.elapsedMs = elapsedMs;
            this.resolvedMode = resolvedMode;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getOutput() {
            return output;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public int getMatchCount() {
            return matchCount;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }

        public ResultMode getResolvedMode() {
            return resolvedMode;
        }
    }

    /** XSLT 转换结果。 */
    public static final class TransformResult {
        private final boolean success;
        private final String output;
        private final String errorMessage;
        private final long elapsedMs;

        private TransformResult(boolean success, String output, String errorMessage, long elapsedMs) {
            this.success = success;
            this.output = output;
            this.errorMessage = errorMessage;
            this.elapsedMs = elapsedMs;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getOutput() {
            return output;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }
    }

    /**
     * 求值 XPath 表达式。
     *
     * @param xml            XML 文本
     * @param expression     XPath 表达式
     * @param mode           期望的结果类型
     * @param namespaceAware true 时按命名空间解析，表达式需带前缀；false 时忽略命名空间，
     *                       {@code /root/child} 这种写法可直接命中带 xmlns 的文档
     */
    public QueryResult query(String xml, String expression, ResultMode mode, boolean namespaceAware) {
        if (xml == null || xml.trim().isEmpty()) {
            return new QueryResult(false, "", "empty.xml", 0, 0, mode);
        }
        if (expression == null || expression.trim().isEmpty()) {
            return new QueryResult(false, "", "empty.expression", 0, 0, mode);
        }
        long started = System.nanoTime();
        try {
            Document document = parse(xml, namespaceAware);
            XPath xpath = XPathFactory.newInstance().newXPath();
            if (namespaceAware) {
                xpath.setNamespaceContext(new DiscoveredNamespaceContext(discoverNamespaces(document)));
            }
            XPathExpression compiled = xpath.compile(expression.trim());
            ResultMode effective = mode == null ? ResultMode.AUTO : mode;
            if (effective == ResultMode.AUTO) {
                NodeList nodes = tryNodeSet(compiled, document);
                if (nodes != null) {
                    return new QueryResult(true, serializeNodes(nodes), null,
                            nodes.getLength(), elapsedMs(started), ResultMode.NODESET);
                }
                String text = (String) compiled.evaluate(document, XPathConstants.STRING);
                return new QueryResult(true, text, null, text.isEmpty() ? 0 : 1,
                        elapsedMs(started), ResultMode.STRING);
            }
            switch (effective) {
                case NODESET: {
                    NodeList nodes = (NodeList) compiled.evaluate(document, XPathConstants.NODESET);
                    return new QueryResult(true, serializeNodes(nodes), null,
                            nodes.getLength(), elapsedMs(started), ResultMode.NODESET);
                }
                case NUMBER: {
                    Double value = (Double) compiled.evaluate(document, XPathConstants.NUMBER);
                    return new QueryResult(true, formatNumber(value), null, 1,
                            elapsedMs(started), ResultMode.NUMBER);
                }
                case BOOLEAN: {
                    Boolean value = (Boolean) compiled.evaluate(document, XPathConstants.BOOLEAN);
                    return new QueryResult(true, String.valueOf(value), null, 1,
                            elapsedMs(started), ResultMode.BOOLEAN);
                }
                default: {
                    String text = (String) compiled.evaluate(document, XPathConstants.STRING);
                    return new QueryResult(true, text, null, text.isEmpty() ? 0 : 1,
                            elapsedMs(started), ResultMode.STRING);
                }
            }
        } catch (Exception error) {
            return new QueryResult(false, "", describe(error), 0, elapsedMs(started), mode);
        }
    }

    /** 以 XSLT 样式表转换 XML。 */
    public TransformResult transform(String xml, String stylesheet, boolean indent) {
        if (xml == null || xml.trim().isEmpty()) {
            return new TransformResult(false, "", "empty.xml", 0);
        }
        if (stylesheet == null || stylesheet.trim().isEmpty()) {
            return new TransformResult(false, "", "empty.stylesheet", 0);
        }
        long started = System.nanoTime();
        try {
            Document source = parse(xml, true);
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            setAttributeQuietly(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
            setAttributeQuietly(factory, XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            factory.setErrorListener(SILENT_TRANSFORM_LISTENER);
            Transformer transformer = factory.newTransformer(
                    new StreamSource(new StringReader(stylesheet)));
            if (indent) {
                transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            }
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(source), new StreamResult(writer));
            return new TransformResult(true, writer.toString(), null, elapsedMs(started));
        } catch (Exception error) {
            return new TransformResult(false, "", describe(error), elapsedMs(started));
        }
    }

    /**
     * 列出文档里声明过的命名空间：前缀 -> URI。
     * 默认命名空间（无前缀）会被映射到 {@link #DEFAULT_NS_PREFIX}，否则 XPath 1.0 无法引用它。
     */
    public Map<String, String> discoverNamespaces(String xml) {
        try {
            return discoverNamespaces(parse(xml, true));
        } catch (Exception error) {
            return Collections.emptyMap();
        }
    }

    private Map<String, String> discoverNamespaces(Document document) {
        Map<String, String> namespaces = new LinkedHashMap<>();
        collectNamespaces(document.getDocumentElement(), namespaces);
        return namespaces;
    }

    private void collectNamespaces(Node node, Map<String, String> target) {
        if (node == null || node.getNodeType() != Node.ELEMENT_NODE) {
            return;
        }
        NamedNodeMap attributes = node.getAttributes();
        for (int i = 0; attributes != null && i < attributes.getLength(); i++) {
            Attr attribute = (Attr) attributes.item(i);
            String name = attribute.getName();
            if ("xmlns".equals(name)) {
                target.putIfAbsent(DEFAULT_NS_PREFIX, attribute.getValue());
            } else if (name.startsWith("xmlns:")) {
                target.putIfAbsent(name.substring("xmlns:".length()), attribute.getValue());
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            collectNamespaces(children.item(i), target);
        }
    }

    private Document parse(String xml, boolean namespaceAware) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(namespaceAware);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeatureQuietly(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeatureQuietly(factory,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        builder.setErrorHandler(SILENT_SAX_HANDLER);
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private NodeList tryNodeSet(XPathExpression compiled, Document document) {
        try {
            return (NodeList) compiled.evaluate(document, XPathConstants.NODESET);
        } catch (Exception notANodeSet) {
            return null;
        }
    }

    private String serializeNodes(NodeList nodes) throws Exception {
        if (nodes.getLength() == 0) {
            return "";
        }
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < nodes.getLength(); i++) {
            output.append('[').append(i + 1).append("] ")
                    .append(serializeNode(nodes.item(i)));
            if (i < nodes.getLength() - 1) {
                output.append('\n');
            }
        }
        return output.toString();
    }

    private String serializeNode(Node node) throws Exception {
        switch (node.getNodeType()) {
            case Node.ATTRIBUTE_NODE:
                return node.getNodeName() + "=\"" + node.getNodeValue() + "\"";
            case Node.TEXT_NODE:
            case Node.CDATA_SECTION_NODE:
                return String.valueOf(node.getNodeValue()).trim();
            case Node.COMMENT_NODE:
                return "<!--" + node.getNodeValue() + "-->";
            default:
                return serializeElement(node);
        }
    }

    private String serializeElement(Node node) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(node), new StreamResult(writer));
        return writer.toString().trim();
    }

    private static String formatNumber(Double value) {
        if (value == null || value.isNaN()) {
            return "NaN";
        }
        if (value == Math.floor(value) && !value.isInfinite()) {
            return String.valueOf(value.longValue());
        }
        return String.valueOf(value);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getMessage() == null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isEmpty() ? cause.getClass().getSimpleName() : message;
    }

    private static void setFeatureQuietly(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception unsupportedByParser) {
            // 不同 JAXP 实现支持的加固开关不同，缺一个不影响其余开关生效。
        }
    }

    private static void setAttributeQuietly(TransformerFactory factory, String name, String value) {
        try {
            factory.setAttribute(name, value);
        } catch (Exception unsupportedByProcessor) {
            // 同上：属性不被支持时保持默认行为。
        }
    }

    /** 由文档扫描得到的前缀映射，供 XPath 1.0 引用带命名空间的节点。 */
    private static final class DiscoveredNamespaceContext implements NamespaceContext {
        private final Map<String, String> byPrefix;

        DiscoveredNamespaceContext(Map<String, String> byPrefix) {
            this.byPrefix = byPrefix;
        }

        @Override
        public String getNamespaceURI(String prefix) {
            if (prefix == null) {
                throw new IllegalArgumentException("prefix is required");
            }
            String uri = byPrefix.get(prefix);
            return uri == null ? XMLConstants.NULL_NS_URI : uri;
        }

        @Override
        public String getPrefix(String namespaceURI) {
            for (Map.Entry<String, String> entry : byPrefix.entrySet()) {
                if (entry.getValue().equals(namespaceURI)) {
                    return entry.getKey();
                }
            }
            return null;
        }

        @Override
        public Iterator<String> getPrefixes(String namespaceURI) {
            List<String> prefixes = new ArrayList<>();
            for (Map.Entry<String, String> entry : byPrefix.entrySet()) {
                if (entry.getValue().equals(namespaceURI)) {
                    prefixes.add(entry.getKey());
                }
            }
            return prefixes.iterator();
        }
    }
}
