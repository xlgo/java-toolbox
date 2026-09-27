package com.aqishi.toolbox.feature.system.domain;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maven 坐标。来源有两种：JAR 里的 {@code META-INF/maven/<g>/<a>/pom.properties}（可靠），
 * 或从文件名 {@code guava-31.1-jre.jar} 猜测（{@link #guessed()} 为真，groupId 为空）。
 *
 * @param source 坐标来源的条目路径；从文件名猜测时为文件名
 */
public record MavenCoordinates(String groupId, String artifactId, String version, String source, boolean guessed) {

    /** 文件名里第一个“-数字”之后视为版本：commons-lang3-3.12.0.jar → commons-lang3 / 3.12.0。 */
    private static final Pattern FILE_NAME = Pattern.compile("^(.+?)-(\\d[\\w.\\-+]*?)\\.(?:jar|war|ear)$",
            Pattern.CASE_INSENSITIVE);

    /** {@code groupId:artifactId:version}；缺少的部分省略。 */
    public String gav() {
        StringBuilder text = new StringBuilder();
        if (groupId != null && !groupId.isEmpty()) {
            text.append(groupId).append(':');
        }
        text.append(artifactId == null ? "?" : artifactId);
        if (version != null && !version.isEmpty()) {
            text.append(':').append(version);
        }
        return text.toString();
    }

    /** 解析 pom.properties；缺少 artifactId 时返回 {@code null}。 */
    public static MavenCoordinates fromPomProperties(byte[] content, String entryName) {
        Properties properties = new Properties();
        try {
            properties.load(new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.ISO_8859_1));
        } catch (IOException | IllegalArgumentException malformed) {
            return null;
        }
        String artifactId = trimToNull(properties.getProperty("artifactId"));
        if (artifactId == null) {
            return null;
        }
        return new MavenCoordinates(trimToNull(properties.getProperty("groupId")), artifactId,
                trimToNull(properties.getProperty("version")), entryName, false);
    }

    /** 从文件名猜坐标；猜不出返回 {@code null}。 */
    public static MavenCoordinates guessFromFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String base = fileName.substring(Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1);
        Matcher matcher = FILE_NAME.matcher(base);
        if (!matcher.matches()) {
            return null;
        }
        return new MavenCoordinates(null, matcher.group(1), matcher.group(2), base, true);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
