package com.aqishi.toolbox.feature.system.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.aqishi.toolbox.feature.system.domain.StackTraceParser.*;
import com.aqishi.toolbox.util.I18n;

import org.junit.jupiter.api.Test;

import java.util.List;

class StackTraceParserTest {
    private static final String NESTED =
            "Exception in thread \"main\" java.lang.IllegalStateException: outer\n"
                    + "\tat com.example.App.run(App.java:10)\n"
                    + "\tat java.base/java.lang.Thread.run(Thread.java:833)\n"
                    + "\tSuppressed: java.io.IOException: close failed\n"
                    + "\t\tat com.example.Res.close(Res.java:5)\n"
                    + "\tCaused by: java.lang.ArithmeticException: inner of suppressed\n"
                    + "\t\tat com.example.Res.calc(Res.java:9)\n"
                    + "Caused by: java.lang.NullPointerException: Cannot invoke \"String.length()\"\n"
                    + "\tat app//com.example.Dao.load(Dao.java:42) ~[app.jar:1.0]\n"
                    + "\t... 2 more\n";

    @Test
    void parsesCausedBySuppressedAndModulePrefixes() {
        List<Trace> traces = new StackTraceParser().parse(NESTED);
        assertEquals(1, traces.size());
        Trace trace = traces.get(0);
        assertEquals(4, trace.blocks().size());
        assertEquals("java.lang.IllegalStateException", trace.top().type());
        assertEquals("java.lang.Thread", trace.top().frames().get(1).className());
        // 被抑制异常内部的 Caused by 不属于主链
        assertEquals(2, trace.mainChain().size());
        Block root = trace.rootCause();
        assertEquals("NullPointerException", root.simpleType());
        assertEquals("com.example.Dao", root.frames().get(0).className());
        assertEquals("Dao.java:42", root.frames().get(0).location());
        assertEquals(2, root.omitted());
    }

    @Test
    void splitsSeparateTracesInsideLogsAndKeepsMultiLineMessages() {
        String log =
                "10:00 INFO started\n"
                        + "10:01 ERROR failed\n"
                        + "com.example.BizFault: line one\n"
                        + "line two\n"
                        + "\tat com.example.A.a(A.java:1)\n"
                        + "10:02 WARN retry\n"
                        + "java.lang.RuntimeException\n"
                        + "\tat com.example.B.b(B.java:2)\n"
                        + "\tat com.example.B.c(Native Method)\n";
        List<Trace> traces = new StackTraceParser().parse(log);
        assertEquals(2, traces.size());
        assertEquals("line one\nline two", traces.get(0).top().message());
        assertEquals("", traces.get(1).top().message());
        assertEquals("Native Method", traces.get(1).top().frames().get(1).location());
    }

    @Test
    void ignoresHeaderLikeLinesWithoutFrames() {
        assertTrue(new StackTraceParser().parse("com.example.Config: loaded\nplain text").isEmpty());
    }

    @Test
    void reportFindsRootCauseAppFrameAdviceAndDeduplicates() {
        String twice = NESTED + "\n" + NESTED;
        String report = new StackTraceReport("", true).analyze(twice);
        assertTrue(report.contains(I18n.get("stack.summary", 2, 1)), report);
        assertTrue(report.contains(I18n.get("stack.group", 1, 2)), report);
        assertTrue(report.contains("com.example.Dao.load(Dao.java:42)"), report);
        assertTrue(report.contains(I18n.get("stack.hint.NullPointerException")), report);
        assertTrue(report.contains(I18n.get("stack.folded", 1)) || report.contains("Thread.run"), report);
    }

    @Test
    void appPrefixesOverrideFrameworkHeuristicAndProxiesAreNeverApp() {
        StackTraceReport report = new StackTraceReport("org.springframework.demo", false);
        assertTrue(report.isAppFrame(new Frame("org.springframework.demo.A", "m", "A.java:1", "")));
        assertFalse(report.isAppFrame(new Frame("com.example.A", "m", "A.java:1", "")));
        assertFalse(new StackTraceReport("", false)
                .isAppFrame(new Frame("com.example.Svc$$SpringCGLIB$$0", "m", "<generated>", "")));
    }

    @Test
    void outOfMemoryVariantsMapToSpecificHints() {
        String trace = "java.lang.OutOfMemoryError: Metaspace\n\tat com.example.A.a(A.java:1)\n";
        assertTrue(new StackTraceReport("", true).analyze(trace)
                .contains(I18n.get("stack.hint.oom.metaspace")));
    }

    @Test
    void rejectsTextWithoutStackTrace() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> new StackTraceReport("", true).analyze("hello"));
        assertEquals(I18n.get("stack.empty"), error.getMessage());
    }
}
