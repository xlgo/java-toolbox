package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.feature.system.domain.JarReport;
import com.aqishi.toolbox.feature.system.domain.MavenCoordinates;
import com.aqishi.toolbox.util.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JAR 分析结果的纯文本渲染与枚举本地化。
 */
final class JarText {

    /** 报告里每节最多列出的条目数。 */
    private static final int REPORT_LIMIT = 50;

    private JarText() {
    }

    static String layoutLabel(JarReport.Layout layout) {
        switch (layout) {
            case SPRING_BOOT_JAR:
                return I18n.get("tool.jarinspector.layout.springBootJar");
            case SPRING_BOOT_WAR:
                return I18n.get("tool.jarinspector.layout.springBootWar");
            case WAR:
                return I18n.get("tool.jarinspector.layout.war");
            case EAR:
                return I18n.get("tool.jarinspector.layout.ear");
            default:
                return I18n.get("tool.jarinspector.layout.plain");
        }
    }

    static String warningLabel(JarReport.Warning.Code code) {
        switch (code) {
            case ENTRY_TOO_LARGE:
                return I18n.get("tool.jarinspector.warning.entryTooLarge");
            case COMPRESSION_RATIO:
                return I18n.get("tool.jarinspector.warning.compressionRatio");
            case CORRUPT_CLASS:
                return I18n.get("tool.jarinspector.warning.corruptClass");
            case NESTED_UNREADABLE:
                return I18n.get("tool.jarinspector.warning.nestedUnreadable");
            case NESTED_TOO_DEEP:
                return I18n.get("tool.jarinspector.warning.nestedTooDeep");
            case NESTED_ENTRY_LIMIT:
                return I18n.get("tool.jarinspector.warning.nestedEntryLimit");
            default:
                return I18n.get("tool.jarinspector.warning.badManifest");
        }
    }

    /** 坐标文本：pom.properties 的 GAV；只能按文件名猜时标注“推测”。 */
    static String coordinatesText(MavenCoordinates coordinates) {
        if (coordinates == null) {
            return "-";
        }
        return coordinates.guessed()
                ? I18n.get("tool.jarinspector.value.guessed", coordinates.gav()) : coordinates.gav();
    }

    /** 顶层与所有嵌套库的警告，嵌套库的条目路径前加上库名。 */
    static List<String> flattenWarnings(JarReport report) {
        List<String> lines = new ArrayList<>();
        collectWarnings(report, "", lines);
        return lines;
    }

    private static void collectWarnings(JarReport report, String prefix, List<String> lines) {
        for (JarReport.Warning warning : report.warnings()) {
            String detail = warning.detail() == null ? "" : "  (" + warning.detail() + ")";
            lines.add(warningLabel(warning.code()) + ": " + prefix + warning.entry() + detail);
        }
        for (JarReport.NestedLibrary library : report.libraries()) {
            if (library.report() != null) {
                collectWarnings(library.report(), prefix + library.path() + "!/", lines);
            }
        }
    }

    /** 概览文本：坐标、形态、模块、签名、Spring Boot 信息与警告。 */
    static String overview(JarReport report) {
        StringBuilder text = new StringBuilder();
        line(text, "tool.jarinspector.overview.file", report.name());
        line(text, "tool.jarinspector.overview.layout", layoutLabel(report.layout()));
        text.append(I18n.get("tool.jarinspector.overview.entries", String.valueOf(report.entryCount()),
                JarUi.formatSize(report.totalSize()), JarUi.formatSize(report.compressedSize()))).append('\n');
        text.append(I18n.get("tool.jarinspector.overview.classes", String.valueOf(report.classCount()),
                JarUi.releaseLabel(report.maxMajor()))).append('\n');
        if (!report.libraries().isEmpty()) {
            line(text, "tool.jarinspector.overview.maxWithLibraries",
                    JarUi.releaseLabel(report.maxMajorIncludingLibraries()));
        }
        if (report.previewClasses() > 0) {
            line(text, "tool.jarinspector.overview.preview", String.valueOf(report.previewClasses()));
        }
        text.append('\n').append(I18n.get("tool.jarinspector.overview.coordinates")).append('\n');
        if (report.coordinates().isEmpty()) {
            text.append("  -\n");
        }
        for (MavenCoordinates coordinates : report.coordinates()) {
            text.append("  ").append(coordinates.gav()).append("  [").append(coordinates.source()).append("]\n");
        }
        text.append('\n');
        line(text, "tool.jarinspector.overview.signed", report.signed()
                ? I18n.get("tool.jarinspector.value.yes") + "  " + String.join(", ", report.signatureFiles())
                : I18n.get("tool.jarinspector.value.no"));
        line(text, "tool.jarinspector.overview.multiRelease", report.multiRelease()
                ? I18n.get("tool.jarinspector.value.yes") + "  " + report.versionedClasses().keySet()
                : I18n.get("tool.jarinspector.value.no"));
        if (report.springBoot() != null) {
            JarReport.SpringBootInfo boot = report.springBoot();
            text.append('\n').append(I18n.get("tool.jarinspector.overview.springBoot",
                    JarUi.orDash(boot.version()), JarUi.orDash(boot.startClass()),
                    String.valueOf(boot.loaderClassCount()))).append('\n');
            if (!boot.layers().isEmpty()) {
                line(text, "tool.jarinspector.overview.layers", String.join(", ", boot.layers()));
            }
            if (!boot.classpathIndex().isEmpty()) {
                line(text, "tool.jarinspector.overview.classpathIndex", String.valueOf(boot.classpathIndex().size()));
            }
        }
        if (report.module() != null) {
            text.append('\n').append(I18n.get("tool.jarinspector.overview.module", report.moduleEntry()))
                    .append('\n').append(ClassText.module(report.module()));
        } else if (report.manifestValue("Automatic-Module-Name") != null) {
            text.append('\n');
            line(text, "tool.jarinspector.overview.automaticModule", report.manifestValue("Automatic-Module-Name"));
        }
        List<String> warnings = flattenWarnings(report);
        if (!warnings.isEmpty()) {
            text.append('\n').append(I18n.get("tool.jarinspector.overview.warnings", String.valueOf(warnings.size())))
                    .append('\n');
            for (int i = 0; i < warnings.size() && i < REPORT_LIMIT; i++) {
                text.append("  ").append(warnings.get(i)).append('\n');
            }
        }
        return text.toString();
    }

    /** 复制用的完整报告：概览 + MANIFEST + 版本分布 + 嵌套库 + SPI。 */
    static String report(JarReport report) {
        StringBuilder text = new StringBuilder(I18n.get("tool.jarinspector.report.title")).append('\n');
        text.append("==========================================\n").append(overview(report));
        text.append('\n').append(I18n.get("tool.jarinspector.tab.manifest")).append('\n');
        for (Map.Entry<String, String> entry : report.manifest().entrySet()) {
            text.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        text.append('\n').append(I18n.get("tool.jarinspector.tab.versions")).append('\n');
        report.majorHistogram().forEach((major, count) ->
                text.append("  ").append(JarUi.releaseLabel(major)).append(": ").append(count).append('\n'));
        report.versionedClasses().forEach((release, stats) -> text.append("  META-INF/versions/").append(release)
                .append(": ").append(stats.classCount()).append("  max ").append(JarUi.releaseLabel(stats.maxMajor()))
                .append('\n'));
        if (!report.libraries().isEmpty()) {
            text.append('\n').append(I18n.get("tool.jarinspector.tab.libraries")).append('\n');
            for (JarReport.NestedLibrary library : report.libraries()) {
                text.append("  ").append(library.path()).append("  ")
                        .append(coordinatesText(library.bestCoordinates())).append("  ")
                        .append(library.report() == null ? "-" : JarUi.releaseLabel(library.report().maxMajor()))
                        .append('\n');
            }
        }
        if (!report.services().isEmpty()) {
            text.append('\n').append(I18n.get("tool.jarinspector.tab.services")).append('\n');
            for (JarReport.ServiceFile service : report.services()) {
                text.append("  ").append(service.service()).append(" -> ")
                        .append(String.join(", ", service.providers())).append('\n');
            }
        }
        return text.toString();
    }

    private static void line(StringBuilder text, String labelKey, String value) {
        text.append(I18n.get(labelKey)).append(": ").append(value).append('\n');
    }
}
