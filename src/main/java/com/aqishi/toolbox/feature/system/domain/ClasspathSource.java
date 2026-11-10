package com.aqishi.toolbox.feature.system.domain;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Classpath 中的一个加载源（JAR、嵌套 JAR 或类目录），带有明确的类加载先后次序。
 *
 * @param order       在类路径中的优先顺序（1-based，数值越小越先被类加载器检索）
 * @param kind        源类型
 * @param path        宿主文件或目录路径
 * @param nestedPath  若为 Fat Jar / 嵌套 Jar 中的内嵌库，记录其内部相对路径（如 BOOT-INF/lib/foo.jar）
 * @param coordinates 识别出的 Maven 坐标（可能为 null）
 * @param version     版本号（来自 pom.properties、MANIFEST 或文件名猜测）
 */
public record ClasspathSource(
        int order,
        Kind kind,
        Path path,
        String nestedPath,
        MavenCoordinates coordinates,
        String version
) {
    public enum Kind {
        JAR,
        NESTED_JAR,
        DIRECTORY
    }

    public ClasspathSource {
        if (order < 1) {
            throw new IllegalArgumentException("order must be >= 1");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(path, "path");
    }

    /**
     * 规范化展示名称，如 {@code guava-31.1-jre.jar} 或 {@code app.jar!/BOOT-INF/lib/foo.jar}。
     */
    public String displayName() {
        if (kind == Kind.DIRECTORY) {
            return path.toString();
        }
        Path fileName = path.getFileName();
        String base = (fileName == null) ? path.toString() : fileName.toString();
        return nestedPath == null ? base : base + "!/" + nestedPath;
    }

    /**
     * 完整路径定位串。
     */
    public String fullPath() {
        return nestedPath == null ? path.toString() : path + "!/" + nestedPath;
    }

    /**
     * 附带优先级的短标签，如 {@code [#1] foo-1.0.jar}。
     */
    public String orderLabel() {
        return "[#" + order + "] " + displayName();
    }
}
