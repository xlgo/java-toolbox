package com.aqishi.toolbox.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JavaStringLiterals} 的自测。
 *
 * <p>i18n 硬编码门禁完全依赖这个扫描器的判断，扫描器错一处，门禁就会漏放或误拦。
 * 这里覆盖它必须分清的几种边界：注释、转义、字符字面量、文本块。</p>
 */
class JavaStringLiteralsTest {

    @Test
    @DisplayName("摘出普通字符串字面量，按出现顺序")
    void extractsPlainLiteralsInOrder() {
        List<String> literals = JavaStringLiterals.scan("String a = \"first\"; String b = \"second\";");
        assertEquals(List.of("first", "second"), literals);
    }

    @Test
    @DisplayName("忽略行注释与块注释中的内容")
    void ignoresComments() {
        String source = String.join("\n",
                "// \"行注释里的中文\"",
                "/* \"块注释里的中文\" */",
                "/** Javadoc 中文说明，不该被当成文案 */",
                "String real = \"真正的文案\";");
        assertEquals(List.of("真正的文案"), JavaStringLiterals.scan(source));
    }

    @Test
    @DisplayName("转义引号不终止字面量")
    void handlesEscapedQuotes() {
        List<String> literals = JavaStringLiterals.scan("String a = \"say \\\"hi\\\" now\"; String b = \"next\";");
        assertEquals(List.of("say \\\"hi\\\" now", "next"), literals);
    }

    @Test
    @DisplayName("结尾反斜杠的转义不会吞掉后续字面量")
    void handlesTrailingBackslash() {
        List<String> literals = JavaStringLiterals.scan("String sep = \"\\\\\"; String name = \"中文\";");
        assertEquals(List.of("\\\\", "中文"), literals);
    }

    @Test
    @DisplayName("字符字面量中的引号不会让后续配对错位")
    void skipsCharLiteralsContainingQuote() {
        List<String> literals = JavaStringLiterals.scan("char q = '\"'; String after = \"中文\";");
        assertEquals(List.of("中文"), literals);
    }

    @Test
    @DisplayName("转义的字符字面量同样正确跳过")
    void skipsEscapedCharLiteral() {
        List<String> literals = JavaStringLiterals.scan("char c = '\\''; String after = \"中文\";");
        assertEquals(List.of("中文"), literals);
    }

    @Test
    @DisplayName("识别文本块，并在其后继续正常扫描")
    void handlesTextBlocks() {
        String source = "String tpl = \"\"\"\n  中文模板\n  \"\"\"; String after = \"尾部\";";
        List<String> literals = JavaStringLiterals.scan(source);
        assertEquals(2, literals.size());
        assertTrue(literals.get(0).contains("中文模板"));
        assertEquals("尾部", literals.get(1));
    }

    @Test
    @DisplayName("未闭合的字符串不会吞掉整个文件")
    void unterminatedLiteralStopsAtLineEnd() {
        List<String> literals = JavaStringLiterals.scan("String bad = \"未闭合\nString good = \"文案\";");
        assertTrue(literals.contains("未闭合"));
    }

    @Test
    @DisplayName("汉字判定只认汉字，不误判 ASCII 与日文假名")
    void detectsHanOnly() {
        assertTrue(JavaStringLiterals.containsHan("保存"));
        assertTrue(JavaStringLiterals.containsHan("Save 保存"));
        assertFalse(JavaStringLiterals.containsHan("Save"));
        assertFalse(JavaStringLiterals.containsHan(""));
        assertFalse(JavaStringLiterals.containsHan("てすと"));
    }
}
