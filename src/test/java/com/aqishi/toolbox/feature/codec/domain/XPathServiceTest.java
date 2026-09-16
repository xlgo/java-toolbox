package com.aqishi.toolbox.feature.codec.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XPathServiceTest {

    private XPathService service;

    @BeforeEach
    void setUp() {
        service = new XPathService();
    }

    @Test
    void selectsAttributesWithNumericPredicate() {
        XPathService.QueryResult result = service.query(XPathService.SAMPLE_XML,
                "//item[@price>100]/@sku", XPathService.ResultMode.AUTO, false);

        assertTrue(result.isSuccess());
        assertEquals(2, result.getMatchCount());
        assertTrue(result.getOutput().contains("A-01"));
        assertTrue(result.getOutput().contains("B-11"));
        assertFalse(result.getOutput().contains("A-07"));
    }

    @Test
    void autoModeFallsBackToStringForScalarExpressions() {
        XPathService.QueryResult result = service.query(XPathService.SAMPLE_XML,
                "count(//item)", XPathService.ResultMode.AUTO, false);

        assertTrue(result.isSuccess());
        assertEquals(XPathService.ResultMode.STRING, result.getResolvedMode());
        assertEquals("3", result.getOutput());
    }

    @Test
    void numberModeDropsTrailingZeroForWholeValues() {
        XPathService.QueryResult result = service.query(XPathService.SAMPLE_XML,
                "count(//order)", XPathService.ResultMode.NUMBER, false);

        assertTrue(result.isSuccess());
        assertEquals("2", result.getOutput());
    }

    @Test
    void serializesElementNodesAsXmlFragments() {
        XPathService.QueryResult result = service.query(XPathService.SAMPLE_XML,
                "//order[@id='1002']/customer", XPathService.ResultMode.NODESET, false);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getMatchCount());
        assertTrue(result.getOutput().contains("<customer"));
        assertTrue(result.getOutput().contains("Bob"));
    }

    /** 不开命名空间感知时，带 xmlns 的文档也能用最朴素的路径命中——这是最常见的踩坑点。 */
    @Test
    void ignoresNamespacesWhenNotNamespaceAware() {
        String xml = "<root xmlns=\"urn:example\"><child>value</child></root>";

        XPathService.QueryResult result = service.query(xml, "/root/child",
                XPathService.ResultMode.STRING, false);

        assertTrue(result.isSuccess());
        assertEquals("value", result.getOutput());
    }

    /** 开了命名空间感知，默认命名空间要靠自动分配的 ns 前缀引用。 */
    @Test
    void mapsDefaultNamespaceToGeneratedPrefix() {
        String xml = "<root xmlns=\"urn:example\"><child>value</child></root>";

        XPathService.QueryResult bare = service.query(xml, "/root/child",
                XPathService.ResultMode.STRING, true);
        XPathService.QueryResult prefixed = service.query(xml,
                "/" + XPathService.DEFAULT_NS_PREFIX + ":root/" + XPathService.DEFAULT_NS_PREFIX + ":child",
                XPathService.ResultMode.STRING, true);

        assertTrue(bare.isSuccess());
        assertEquals("", bare.getOutput());
        assertTrue(prefixed.isSuccess());
        assertEquals("value", prefixed.getOutput());
    }

    @Test
    void discoversDeclaredNamespaces() {
        String xml = "<root xmlns=\"urn:default\" xmlns:x=\"urn:extra\"><x:child/></root>";

        Map<String, String> namespaces = service.discoverNamespaces(xml);

        assertEquals("urn:default", namespaces.get(XPathService.DEFAULT_NS_PREFIX));
        assertEquals("urn:extra", namespaces.get("x"));
    }

    @Test
    void reportsInvalidExpression() {
        XPathService.QueryResult result = service.query(XPathService.SAMPLE_XML,
                "//item[", XPathService.ResultMode.AUTO, false);

        assertFalse(result.isSuccess());
        assertFalse(result.getErrorMessage().isEmpty());
    }

    @Test
    void reportsMalformedXml() {
        XPathService.QueryResult result = service.query("<root><unclosed>", "/root",
                XPathService.ResultMode.AUTO, false);

        assertFalse(result.isSuccess());
    }

    @Test
    void reportsMissingInputWithStableCodes() {
        assertEquals("empty.xml", service.query("", "/root",
                XPathService.ResultMode.AUTO, false).getErrorMessage());
        assertEquals("empty.expression", service.query("<root/>", " ",
                XPathService.ResultMode.AUTO, false).getErrorMessage());
        assertEquals("empty.stylesheet", service.transform("<root/>", "", true).getErrorMessage());
    }

    /** 外部实体必须被挡住：这个工具会被用来打开来路不明的样本。 */
    @Test
    void doesNotResolveExternalEntities() {
        String xml = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<root>&xxe;</root>";

        XPathService.QueryResult result = service.query(xml, "/root",
                XPathService.ResultMode.STRING, false);

        assertFalse(result.isSuccess() && result.getOutput().contains("root:"));
    }

    @Test
    void transformsWithStylesheet() {
        XPathService.TransformResult result =
                service.transform(XPathService.SAMPLE_XML, XPathService.SAMPLE_XSLT, true);

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("1001"));
        assertTrue(result.getOutput().contains("Alice"));
        assertTrue(result.getOutput().contains("Bob"));
    }

    @Test
    void reportsBrokenStylesheet() {
        XPathService.TransformResult result = service.transform(XPathService.SAMPLE_XML,
                "<xsl:stylesheet version=\"1.0\"><broken></xsl:stylesheet>", true);

        assertFalse(result.isSuccess());
    }
}
