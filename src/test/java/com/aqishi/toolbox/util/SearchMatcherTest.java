package com.aqishi.toolbox.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchMatcherTest {

    @Test
    @DisplayName("空模式或纯空白匹配所有字符串")
    void emptyOrBlankMatchesEverything() {
        SearchMatcher matcher1 = SearchMatcher.of(null);
        SearchMatcher matcher2 = SearchMatcher.of("");
        SearchMatcher matcher3 = SearchMatcher.of("   ");

        for (SearchMatcher m : List.of(matcher1, matcher2, matcher3)) {
            assertTrue(m.test("order-group"));
            assertTrue(m.test(""));
            assertTrue(m.test("anything"));
        }
    }

    @Test
    @DisplayName("非空模式遇到 null 目标返回 false")
    void nonNullPatternRejectsNullTarget() {
        assertFalse(SearchMatcher.of("order").test(null));
        assertFalse(SearchMatcher.of("order*").test(null));
        assertFalse(SearchMatcher.of("regex:.*").test(null));
    }

    @Test
    @DisplayName("普通文本进行子串包含匹配且忽略大小写")
    void plainSubstringContainsIgnoreCase() {
        SearchMatcher matcher = SearchMatcher.of("order");
        assertTrue(matcher.test("order"));
        assertTrue(matcher.test("ORDER-consumer"));
        assertTrue(matcher.test("my_order_group"));
        assertFalse(matcher.test("user-group"));
    }

    @Test
    @DisplayName("空格分隔的多关键字按 AND 逻辑匹配")
    void multiTokenAndMatching() {
        SearchMatcher matcher = SearchMatcher.of("order dev");
        assertTrue(matcher.test("order-service-dev"));
        assertTrue(matcher.test("dev-order"));
        assertTrue(matcher.test("ORDER_SERVICE_DEV"));
        assertFalse(matcher.test("order-service-prod"));
        assertFalse(matcher.test("user-service-dev"));
    }

    @Test
    @DisplayName("星号通配符前缀匹配")
    void wildcardPrefixMatching() {
        SearchMatcher matcher = SearchMatcher.of("order*");
        assertTrue(matcher.test("order"));
        assertTrue(matcher.test("order-group"));
        assertTrue(matcher.test("ORDER_SERVICE"));
        assertFalse(matcher.test("my-order-group"));
        assertFalse(matcher.test("user-order"));
    }

    @Test
    @DisplayName("星号通配符后缀匹配")
    void wildcardSuffixMatching() {
        SearchMatcher matcher = SearchMatcher.of("*group");
        assertTrue(matcher.test("group"));
        assertTrue(matcher.test("my-group"));
        assertTrue(matcher.test("ORDER-GROUP"));
        assertFalse(matcher.test("group-1"));
        assertFalse(matcher.test("my-group-test"));
    }

    @Test
    @DisplayName("星号通配符中间通配与多星号匹配")
    void wildcardInfixAndMultipleStars() {
        SearchMatcher matcher = SearchMatcher.of("order*svc");
        assertTrue(matcher.test("ordersvc"));
        assertTrue(matcher.test("order-svc"));
        assertTrue(matcher.test("order-test-demo-svc"));
        assertFalse(matcher.test("order-svc-extra"));
        assertFalse(matcher.test("my-order-svc"));

        SearchMatcher starOnly = SearchMatcher.of("*");
        assertTrue(starOnly.test("any-group-id"));
        assertTrue(starOnly.test(""));

        SearchMatcher multiStars = SearchMatcher.of("**order**");
        assertTrue(multiStars.test("my-order-group"));
    }

    @Test
    @DisplayName("问号通配符单字符匹配")
    void questionMarkSingleCharMatching() {
        SearchMatcher matcher = SearchMatcher.of("grp-?");
        assertTrue(matcher.test("grp-1"));
        assertTrue(matcher.test("grp-a"));
        assertFalse(matcher.test("grp-"));
        assertFalse(matcher.test("grp-12"));
    }

    @Test
    @DisplayName("包含正则元字符时的通配符安全转义")
    void escapesRegexMetaCharactersInWildcard() {
        SearchMatcher matcher = SearchMatcher.of("app.*.v1");
        assertTrue(matcher.test("app.order.v1"));
        assertTrue(matcher.test("app.user.v1"));
        assertFalse(matcher.test("appXorderYv1"));

        SearchMatcher special = SearchMatcher.of("c++*lib(1)");
        assertTrue(special.test("c++-my-lib(1)"));
        assertFalse(special.test("c++-my-lib(2)"));
    }

    @Test
    @DisplayName("正则模式支持 regex 和 r 前缀匹配")
    void regexModeMatching() {
        SearchMatcher regex1 = SearchMatcher.of("regex:^order-\\d+$");
        assertTrue(regex1.test("order-123"));
        assertTrue(regex1.test("ORDER-999"));
        assertFalse(regex1.test("order-abc"));

        SearchMatcher regex2 = SearchMatcher.of("r:\\d{3}");
        assertTrue(regex2.test("group-100"));
        assertFalse(regex2.test("group-10"));

        // 未闭合非法正则平滑容错不崩溃，回退包含匹配
        SearchMatcher malformed = SearchMatcher.of("regex:[invalid");
        assertTrue(malformed.test("prefix-[invalid-suffix"));
        assertFalse(malformed.test("normal-group"));
    }
}
