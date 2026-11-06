package com.aqishi.toolbox.feature.system.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.aqishi.toolbox.feature.system.domain.JvmOptionParser.*;
import com.aqishi.toolbox.util.I18n;

import org.junit.jupiter.api.Test;

import java.util.List;

class JvmOptionParserTest {

    @Test
    void parsesMixedCommandLineWithQuotesAndComments() {
        String cmd = "nohup java -server -Xms2g -Xmx4g -Xss512k \\\n"
                + "  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 # latency target\n"
                + "  -Dapp.name=\"billing service\" \\\n"
                + "  -javaagent:/opt/agents/pinpoint.jar=agentId=001 \\\n"
                + "  --add-opens java.base/java.lang=ALL-UNNAMED \\\n"
                + "  -jar /opt/app.jar > /dev/null 2>&1 &";

        ParseResult result = new JvmOptionParser().parse(cmd);
        List<OptionToken> options = result.options();

        assertFalse(options.isEmpty());
        assertTrue(options.stream().anyMatch(o -> o.key().equals("-server")));
        assertTrue(options.stream().anyMatch(o -> o.key().equals("-Xms") && o.value().equals("2g")));
        assertTrue(options.stream().anyMatch(o -> o.key().equals("-Xmx") && o.value().equals("4g")));
        assertTrue(options.stream().anyMatch(o -> o.key().equals("-Xss") && o.value().equals("512k")));
        assertTrue(options.stream().anyMatch(o -> o.key().equals("UseG1GC") && o.isBooleanFlag() && o.booleanValue()));
        assertTrue(options.stream().anyMatch(o -> o.key().equals("MaxGCPauseMillis") && o.value().equals("200")));
        assertTrue(options.stream().anyMatch(o -> o.key().equals("app.name") && o.value().equals("billing service")));
        assertTrue(options.stream().anyMatch(o -> o.type() == OptionType.AGENT));
        assertTrue(options.stream().anyMatch(o -> o.type() == OptionType.MODULE_ACCESS));

        // 验证非 JVM 指令被移入 ignored
        List<String> ignored = result.ignoredTokens();
        assertTrue(ignored.contains("nohup"));
        assertTrue(ignored.contains("java"));
        assertTrue(ignored.contains("-jar"));
    }

    @Test
    void detectsGcAndHeapConflicts() {
        String badConfig = "-Xms4g -Xmx2g -XX:+UseG1GC -XX:+UseParallelGC";
        String report = new JvmOptionAnalyzer(17).analyze(badConfig);

        assertTrue(report.contains(I18n.get("jvm.diag.conflict.heap", "4g", "2g")), report);
        assertTrue(report.contains("UseG1GC vs UseParallelGC"), report);
    }

    @Test
    void detectsRemovedAndDeprecatedOptionsForJdkVersion() {
        String oldConfig = "-XX:MaxPermSize=256m -XX:+UseConcMarkSweepGC -XX:+PrintGCDetails";
        // 在 JDK 17 环境下分析：MaxPermSize 和 CMS 已移除，PrintGCDetails 已废弃
        String report = new JvmOptionAnalyzer(17).analyze(oldConfig);

        assertTrue(report.contains(I18n.get("jvm.diag.removed", "-XX:MaxPermSize=256m", 17, I18n.get("jvm.replace.metaspace"))), report);
        assertTrue(report.contains(I18n.get("jvm.diag.removed", "-XX:+UseConcMarkSweepGC", 17, I18n.get("jvm.replace.g1zgc"))), report);
        assertTrue(report.contains(I18n.get("jvm.diag.deprecated", "-XX:+PrintGCDetails", 17, I18n.get("jvm.replace.xlog"))), report);
    }

    @Test
    void detectsDuplicateDefinitions() {
        String duplicate = "-Xmx2g -Xmx4g";
        String report = new JvmOptionAnalyzer(17).analyze(duplicate);

        assertTrue(report.contains(I18n.get("jvm.diag.duplicate", "-Xmx", "-Xmx4g")), report);
    }

    @Test
    void handlesEmptyInputAndRejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> new JvmOptionAnalyzer(17).analyze(""));
        assertThrows(IllegalArgumentException.class, () -> new JvmOptionAnalyzer(17).analyze("echo hello"));
    }
}
