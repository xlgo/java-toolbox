package com.aqishi.toolbox.feature.system.domain;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classpath 输入解析器：将用户输入的文本（多行、分号/冒号分隔、或 java -cp 命令行）解析为有序的路径列表。
 */
public final class ClasspathParser {

    private static final Pattern CP_FLAG_PATTERN = Pattern.compile(
            "(?:^|\\s)(?:-cp|-classpath|--class-path)(?:\\s+|=)(?:\"([^\"]*)\"|'([^']*)'|(\\S+))",
            Pattern.CASE_INSENSITIVE);

    private ClasspathParser() {
    }

    /**
     * 解析文本为有序的路径列表。如果输入包含 java 命令，则优先提取 -cp 参数值。
     *
     * @param rawText 用户输入的原始文本
     * @return 解析并展开通配符后的有序路径列表（已去重，保持首次出现顺序）
     */
    public static List<Path> parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return Collections.emptyList();
        }

        String content = rawText.trim();
        Matcher matcher = CP_FLAG_PATTERN.matcher(content);
        if (matcher.find()) {
            String captured = matcher.group(1);
            if (captured == null) {
                captured = matcher.group(2);
            }
            if (captured == null) {
                captured = matcher.group(3);
            }
            if (captured != null && !captured.isBlank()) {
                content = captured;
            }
        }

        List<String> rawTokens = splitPathTokens(content);
        LinkedHashSet<Path> resolvedPaths = new LinkedHashSet<>();

        for (String token : rawTokens) {
            String clean = cleanToken(token);
            if (clean.isEmpty()) {
                continue;
            }

            if (clean.endsWith("/*") || clean.endsWith("\\*")) {
                String parentStr = clean.substring(0, clean.length() - 2);
                Path parent = Paths.get(parentStr);
                expandWildcard(parent, resolvedPaths);
            } else {
                resolvedPaths.add(Paths.get(clean));
            }
        }

        return new ArrayList<>(resolvedPaths);
    }

    private static List<String> splitPathTokens(String content) {
        List<String> tokens = new ArrayList<>();
        // 支持按换行以及平台路径分隔符拆分
        String[] lines = content.split("\\r?\\n");
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                continue;
            }
            // 分号（Windows）或冒号（Unix）拆分，但冒号需要避免拆坏类似 C:\ 的盘符
            String[] segments = splitByDelimiters(trimmedLine);
            for (String seg : segments) {
                String s = seg.trim();
                if (!s.isEmpty()) {
                    tokens.add(s);
                }
            }
        }
        return tokens;
    }

    private static String[] splitByDelimiters(String line) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int len = line.length();

        for (int i = 0; i < len; i++) {
            char c = line.charAt(i);
            if (c == ';') {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else if (c == ':') {
                // 如果前一个字符是字母且后面是斜杠或反斜杠（形如 C:/ 或 C:\），视为 Windows 盘符，不在此处拆分
                if (current.length() == 1 && Character.isLetter(current.charAt(0))
                        && i + 1 < len && (line.charAt(i + 1) == '/' || line.charAt(i + 1) == '\\')) {
                    current.append(c);
                } else {
                    if (!current.isEmpty()) {
                        parts.add(current.toString());
                        current.setLength(0);
                    }
                }
            } else {
                current.append(c);
            }
        }

        if (!current.isEmpty()) {
            parts.add(current.toString());
        }

        return parts.toArray(new String[0]);
    }

    private static String cleanToken(String token) {
        String s = token.trim();
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            if (s.length() >= 2) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }

    private static void expandWildcard(Path dir, LinkedHashSet<Path> target) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.{jar,JAR}")) {
            List<Path> jars = new ArrayList<>();
            for (Path jar : stream) {
                if (Files.isRegularFile(jar)) {
                    jars.add(jar);
                }
            }
            jars.sort(Path::compareTo);
            target.addAll(jars);
        } catch (IOException ignored) {
            // 忽略不可读目录
        }
    }
}
