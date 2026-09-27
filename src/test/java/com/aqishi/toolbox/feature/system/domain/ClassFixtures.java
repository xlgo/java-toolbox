package com.aqishi.toolbox.feature.system.domain;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 测试夹具：运行时用系统编译器生成 class 文件，用 JarOutputStream 拼装 JAR。
 * 这样不需要把二进制 class/jar 提交进仓库，版本号也由 --release 精确控制。
 */
public final class ClassFixtures {

    private ClassFixtures() {
    }

    /**
     * 编译源码并返回 {@code 条目路径 → 字节}（如 {@code demo/Foo.class}）。
     *
     * @param sources 相对路径（{@code demo/Foo.java}）→ 源码
     */
    public static Map<String, byte[]> compile(Path workDir, int release, Map<String, String> sources)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests need a JDK, not a JRE");
        Path src = Files.createDirectories(workDir.resolve("src-" + release + "-" + System.nanoTime()));
        Path out = Files.createDirectories(workDir.resolve("out-" + release + "-" + System.nanoTime()));
        List<String> args = new ArrayList<>(List.of("--release", String.valueOf(release), "-nowarn",
                "-Xlint:none", "-d", out.toString()));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
            args.add(file.toString());
        }
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int status = compiler.run(null, OutputStream.nullOutputStream(), errors, args.toArray(new String[0]));
        assertEquals(0, status, () -> "javac failed: " + errors.toString(StandardCharsets.UTF_8));
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(out)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile).sorted()::iterator) {
                classes.put(out.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return classes;
    }

    /** 单个源文件的便捷写法。 */
    public static byte[] compileOne(Path workDir, int release, String path, String source) throws IOException {
        Map<String, byte[]> classes = compile(workDir, release, Map.of(path, source));
        String classPath = path.substring(0, path.length() - ".java".length()) + ".class";
        byte[] bytes = classes.get(classPath);
        assertNotNull(bytes, "missing " + classPath + " in " + classes.keySet());
        return bytes;
    }

    /** 在内存里拼一个 JAR；{@code manifest} 为 null 时不写 MANIFEST。 */
    public static byte[] jarBytes(String manifest, Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(buffer)) {
            if (manifest != null) {
                jar.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
                jar.write(manifest.getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                if (entry.getValue() != null) {
                    jar.write(entry.getValue());
                }
                jar.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    public static Path writeJar(Path file, String manifest, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, jarBytes(manifest, entries));
        return file;
    }

    public static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] pomProperties(String groupId, String artifactId, String version) {
        return text("groupId=" + groupId + "\nartifactId=" + artifactId + "\nversion=" + version + "\n");
    }

    /** 带 pom.properties 的库 JAR：一个类 {@code pkg.Name}，按指定 release 编译。 */
    public static byte[] libraryJar(Path workDir, int release, String pkg, String name, String body,
                                    String groupId, String artifactId, String version) throws IOException {
        String path = pkg.replace('.', '/') + "/" + name + ".java";
        byte[] bytes = compileOne(workDir, release, path,
                "package " + pkg + "; public class " + name + " { " + body + " }");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(pkg.replace('.', '/') + "/" + name + ".class", bytes);
        entries.put("META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties",
                pomProperties(groupId, artifactId, version));
        return jarBytes("Manifest-Version: 1.0\r\nImplementation-Version: " + version + "\r\n\r\n", entries);
    }
}
