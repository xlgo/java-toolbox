package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.feature.system.application.ClassSearchService;
import com.aqishi.toolbox.util.I18n;

/**
 * 找类 / 重复类结果的文本渲染与枚举本地化。
 */
final class SearchText {

    private static final int REPORT_LIMIT = 200;

    private SearchText() {
    }

    static String kindLabel(ClassSearchService.QueryKind kind) {
        switch (kind) {
            case FQN:
                return I18n.get("tool.jarinspector.search.kind.fqn");
            case SIMPLE_NAME:
                return I18n.get("tool.jarinspector.search.kind.simpleName");
            case PACKAGE:
                return I18n.get("tool.jarinspector.search.kind.package");
            case CLASS_WILDCARD:
                return I18n.get("tool.jarinspector.search.kind.classWildcard");
            case RESOURCE_NAME:
                return I18n.get("tool.jarinspector.search.kind.resourceName");
            default:
                return I18n.get("tool.jarinspector.search.kind.resourceGlob");
        }
    }

    /** 输入框下方的实时提示：识别成了哪种查询。 */
    static String hint(String text) {
        if (text == null || text.isBlank()) {
            return I18n.get("tool.jarinspector.search.hint");
        }
        return I18n.get("tool.jarinspector.search.detected", kindLabel(ClassSearchService.Query.parse(text).kind()));
    }

    static String warningLabel(ClassSearchService.ScanWarning.Code code) {
        switch (code) {
            case UNREADABLE:
                return I18n.get("tool.jarinspector.scanWarning.unreadable");
            case NOT_A_ZIP:
                return I18n.get("tool.jarinspector.scanWarning.notZip");
            case ENTRY_LIMIT:
                return I18n.get("tool.jarinspector.scanWarning.entryLimit");
            case GUARD_TRIPPED:
                return I18n.get("tool.jarinspector.scanWarning.guardTripped");
            case FILE_LIMIT:
                return I18n.get("tool.jarinspector.scanWarning.fileLimit");
            case INDEX_LIMIT:
                return I18n.get("tool.jarinspector.scanWarning.indexLimit");
            default:
                return I18n.get("tool.jarinspector.scanWarning.corruptClass");
        }
    }

    static String warnings(java.util.List<ClassSearchService.ScanWarning> warnings) {
        StringBuilder text = new StringBuilder();
        for (ClassSearchService.ScanWarning warning : warnings) {
            text.append(warningLabel(warning.code())).append(": ").append(warning.path());
            if (warning.detail() != null && !warning.detail().isEmpty()) {
                text.append("  (").append(warning.detail()).append(')');
            }
            text.append('\n');
        }
        return text.toString();
    }

    static String location(ClassSearchService.Location location) {
        return location.fullPath();
    }

    static String searchReport(ClassSearchService.SearchResult result) {
        StringBuilder text = new StringBuilder(I18n.get("tool.jarinspector.report.searchTitle",
                result.query().text(), kindLabel(result.query().kind()))).append('\n');
        text.append(I18n.get("tool.jarinspector.status.searchDone", String.valueOf(result.hits().size()),
                String.valueOf(result.archivesScanned()), String.valueOf(result.elapsedMillis()))).append("\n\n");
        for (ClassSearchService.Hit hit : result.hits()) {
            text.append(hit.name()).append("  ").append(location(hit.location())).append("  ")
                    .append(hit.entryPath()).append("  ").append(JarUi.orDash(hit.location().version())).append('\n');
        }
        appendWarnings(text, result.warnings());
        return text.toString();
    }

    static String conflictReport(ClassSearchService.ConflictReport report) {
        StringBuilder text = new StringBuilder(I18n.get("tool.jarinspector.report.conflictTitle")).append('\n');
        text.append(I18n.get("tool.jarinspector.status.duplicatesDone", String.valueOf(report.duplicates().size()),
                String.valueOf(report.conflictCount()), String.valueOf(report.archivesScanned()),
                String.valueOf(report.elapsedMillis()))).append("\n\n");
        text.append(I18n.get("tool.jarinspector.tab.duplicates")).append('\n');
        int shown = 0;
        for (ClassSearchService.DuplicateClass duplicate : report.duplicates()) {
            if (shown++ >= REPORT_LIMIT) {
                text.append("  ...\n");
                break;
            }
            text.append(duplicate.contentDiffers() ? "[!] " : "[=] ").append(duplicate.className()).append('\n');
            for (ClassSearchService.Occurrence occurrence : duplicate.occurrences()) {
                text.append("    ").append(location(occurrence.location())).append("  ")
                        .append(JarUi.orDash(occurrence.location().version())).append("  ")
                        .append(occurrence.sha256(), 0, Math.min(12, occurrence.sha256().length())).append('\n');
            }
        }
        text.append('\n').append(I18n.get("tool.jarinspector.tab.pairs")).append('\n');
        for (ClassSearchService.JarPair pair : report.pairs()) {
            text.append("  ").append(I18n.get("tool.jarinspector.report.pairLine", location(pair.first()),
                    location(pair.second()), String.valueOf(pair.sharedClasses()),
                    String.valueOf(pair.differingClasses()))).append('\n');
        }
        appendWarnings(text, report.warnings());
        return text.toString();
    }

    private static void appendWarnings(StringBuilder text, java.util.List<ClassSearchService.ScanWarning> warnings) {
        if (!warnings.isEmpty()) {
            text.append('\n').append(I18n.get("tool.jarinspector.tab.warnings")).append('\n').append(warnings(warnings));
        }
    }
}
