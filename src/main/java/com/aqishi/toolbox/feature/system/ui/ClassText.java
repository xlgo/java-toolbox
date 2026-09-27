package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.feature.system.domain.ClassFileException;
import com.aqishi.toolbox.feature.system.domain.ClassFileInfo;
import com.aqishi.toolbox.feature.system.domain.JarInspector;
import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.I18n;

import java.util.List;

/**
 * class 文件摘要的纯文本渲染与错误码本地化。纯函数，便于测试与复制到剪贴板。
 */
final class ClassText {

    private ClassText() {
    }

    /** 类的形态关键字（Java 源码写法，不翻译）。 */
    static String kindKeyword(ClassFileInfo.Kind kind) {
        switch (kind) {
            case INTERFACE:
                return "interface";
            case ENUM:
                return "enum";
            case ANNOTATION:
                return "@interface";
            case RECORD:
                return "record";
            case MODULE:
                return "module";
            default:
                return "class";
        }
    }

    /** 最常见报错的解释：UnsupportedClassVersionError / Unsupported class file major version。 */
    static String explain(ClassFileInfo info) {
        String release = info.javaRelease();
        if (info.preview()) {
            return I18n.get("tool.jarinspector.explain.preview", release, String.valueOf(info.majorVersion()));
        }
        return I18n.get("tool.jarinspector.explain.class", release, String.valueOf(info.majorVersion()));
    }

    static String summary(ClassFileInfo info) {
        StringBuilder text = new StringBuilder();
        line(text, "tool.jarinspector.summary.className", info.className());
        line(text, "tool.jarinspector.summary.kind", kindKeyword(info.kind())
                + (info.sealed() ? " (sealed)" : ""));
        String version = "Java " + info.javaRelease() + "  (" + info.versionText() + ")";
        if (info.preview()) {
            version += "  " + I18n.get("tool.jarinspector.summary.previewFlag");
        }
        line(text, "tool.jarinspector.summary.version", version);
        line(text, "tool.jarinspector.summary.access", String.join(" ", info.accessNames()));
        line(text, "tool.jarinspector.summary.superClass", JarUi.orDash(info.superClassName()));
        line(text, "tool.jarinspector.summary.interfaces", joined(info.interfaces()));
        line(text, "tool.jarinspector.summary.sourceFile", JarUi.orDash(info.sourceFile()));
        if (info.signature() != null) {
            line(text, "tool.jarinspector.summary.signature", info.signature());
        }
        line(text, "tool.jarinspector.summary.annotations", joined(info.annotations()));
        if (info.deprecated()) {
            line(text, "tool.jarinspector.summary.deprecated", I18n.get("tool.jarinspector.value.yes"));
        }
        if (info.recordComponents() != null) {
            StringBuilder components = new StringBuilder();
            for (ClassFileInfo.Member component : info.recordComponents()) {
                if (components.length() > 0) {
                    components.append(", ");
                }
                components.append(component.renderedType()).append(' ').append(component.name());
            }
            line(text, "tool.jarinspector.summary.recordComponents", JarUi.orDash(components.toString()));
        }
        if (info.permittedSubclasses() != null) {
            line(text, "tool.jarinspector.summary.permitted", joined(info.permittedSubclasses()));
        }
        if (info.nestHost() != null) {
            line(text, "tool.jarinspector.summary.nestHost", info.nestHost());
        }
        if (!info.nestMembers().isEmpty()) {
            line(text, "tool.jarinspector.summary.nestMembers", joined(info.nestMembers()));
        }
        text.append(I18n.get("tool.jarinspector.summary.counts", String.valueOf(info.fields().size()),
                String.valueOf(info.methods().size()), String.valueOf(info.constantPoolCount()))).append('\n');
        line(text, "tool.jarinspector.summary.attributes", joined(info.attributeNames()));
        if (info.module() != null) {
            text.append('\n').append(module(info.module()));
        }
        text.append('\n').append(I18n.get("tool.jarinspector.summary.references",
                String.valueOf(info.referencedClasses().size()))).append('\n');
        for (String name : info.referencedClasses()) {
            text.append("  ").append(name).append('\n');
        }
        return text.toString();
    }

    /** module-info 的源码式渲染。 */
    static String module(ClassFileInfo.ModuleInfo module) {
        StringBuilder text = new StringBuilder();
        text.append(module.open() ? "open module " : "module ").append(module.name());
        if (module.version() != null) {
            text.append('@').append(module.version());
        }
        text.append(" {\n");
        for (ClassFileInfo.Requires requires : module.requires()) {
            text.append("    requires ").append(requires.transitive() ? "transitive " : "")
                    .append(requires.staticPhase() ? "static " : "").append(requires.module()).append(";\n");
        }
        for (ClassFileInfo.Exports exports : module.exports()) {
            text.append("    exports ").append(exports.packageName()).append(targets(exports)).append(";\n");
        }
        for (ClassFileInfo.Exports opens : module.opens()) {
            text.append("    opens ").append(opens.packageName()).append(targets(opens)).append(";\n");
        }
        for (String uses : module.uses()) {
            text.append("    uses ").append(uses).append(";\n");
        }
        for (ClassFileInfo.Provides provides : module.provides()) {
            text.append("    provides ").append(provides.service()).append(" with ")
                    .append(String.join(", ", provides.implementations())).append(";\n");
        }
        text.append("}\n");
        if (module.mainClass() != null) {
            text.append("// Main-Class: ").append(module.mainClass()).append('\n');
        }
        return text.toString();
    }

    private static String targets(ClassFileInfo.Exports exports) {
        return exports.targets().isEmpty() ? "" : " to " + String.join(", ", exports.targets());
    }

    private static void line(StringBuilder text, String labelKey, String value) {
        text.append(I18n.get(labelKey)).append(": ").append(value).append('\n');
    }

    private static String joined(List<String> values) {
        return values.isEmpty() ? "-" : String.join(", ", values);
    }

    /** 读取 / 解析失败的本地化说明。 */
    static String describeError(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.ExecutionException && error.getCause() != null
                ? error.getCause() : error;
        if (cause instanceof ClassFileException) {
            ClassFileException failure = (ClassFileException) cause;
            return I18n.get(classErrorKey(failure.code()), String.valueOf(failure.offset()));
        }
        if (cause instanceof JarInspector.InspectionException) {
            return I18n.get(inspectionErrorKey(((JarInspector.InspectionException) cause).code()));
        }
        return I18n.get("tool.jarinspector.error.read", Errors.describeRoot(cause));
    }

    static String classErrorKey(ClassFileException.Code code) {
        switch (code) {
            case TRUNCATED:
                return "tool.jarinspector.error.class.truncated";
            case BAD_MAGIC:
                return "tool.jarinspector.error.class.badMagic";
            case BAD_VERSION:
                return "tool.jarinspector.error.class.badVersion";
            case BAD_CONSTANT_TAG:
                return "tool.jarinspector.error.class.badConstantTag";
            case BAD_CONSTANT_INDEX:
                return "tool.jarinspector.error.class.badConstantIndex";
            case BAD_UTF8:
                return "tool.jarinspector.error.class.badUtf8";
            case BAD_ATTRIBUTE:
                return "tool.jarinspector.error.class.badAttribute";
            case LIMIT_EXCEEDED:
                return "tool.jarinspector.error.class.limitExceeded";
            default:
                return "tool.jarinspector.error.class.tooLarge";
        }
    }

    static String inspectionErrorKey(JarInspector.InspectionException.Code code) {
        switch (code) {
            case NOT_A_ZIP:
                return "tool.jarinspector.error.jar.notZip";
            case TOO_MANY_ENTRIES:
                return "tool.jarinspector.error.jar.tooManyEntries";
            case TIMEOUT:
                return "tool.jarinspector.error.jar.timeout";
            default:
                return "tool.jarinspector.error.jar.entryNotFound";
        }
    }
}
