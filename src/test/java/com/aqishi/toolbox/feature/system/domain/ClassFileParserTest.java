package com.aqishi.toolbox.feature.system.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassFileParserTest {

    @TempDir
    Path temp;

    private final ClassFileParser parser = new ClassFileParser();

    @ParameterizedTest
    @CsvSource({"8,52", "11,55", "17,61", "21,65"})
    void parsesMajorVersionForRelease(int release, int expectedMajor) throws Exception {
        byte[] bytes = ClassFixtures.compileOne(temp, release, "demo/Plain.java",
                "package demo; public class Plain implements java.io.Serializable, Runnable {"
                        + " private static final long ID = 1L; protected double ratio;"
                        + " public void run() {} public static String join(int a, String[] b) { return null; } }");
        ClassFileInfo info = parser.parse(bytes);

        assertEquals(expectedMajor, info.majorVersion());
        assertEquals(0, info.minorVersion());
        assertFalse(info.preview());
        assertEquals(String.valueOf(release), info.javaRelease());
        assertEquals("demo.Plain", info.className());
        assertEquals("java.lang.Object", info.superClassName());
        assertEquals(java.util.List.of("java.io.Serializable", "java.lang.Runnable"), info.interfaces());
        assertEquals("Plain.java", info.sourceFile());
        assertEquals(ClassFileInfo.Kind.CLASS, info.kind());
        assertTrue(info.accessNames().contains("public"));
        assertEquals(2, info.fields().size());
        ClassFileInfo.Member join = info.methods().stream().filter(m -> m.name().equals("join")).findFirst()
                .orElseThrow();
        assertEquals("(I[Ljava/lang/String;)Ljava/lang/String;", join.descriptor());
        assertEquals("String (int, String[])", join.renderedType());
        assertTrue(ClassFileInfo.methodAccessNames(join.accessFlags()).contains("static"));
        // header-only 解析与完整解析一致
        assertEquals(expectedMajor, ClassFileParser.parseHeader(bytes, 8).majorVersion());
    }

    @Test
    void parsesRecordSealedEnumAnnotationAndDefaultMethods() throws Exception {
        Map<String, byte[]> classes = ClassFixtures.compile(temp, 17, Map.of(
                "demo/Point.java", "package demo; public record Point(int x, String label) {}",
                "demo/Shape.java", "package demo; public sealed interface Shape permits Circle, Square {"
                        + " default double area() { return 0; } }",
                "demo/Circle.java", "package demo; public final class Circle implements Shape {}",
                "demo/Square.java", "package demo; public non-sealed class Square implements Shape {}",
                "demo/Color.java", "package demo; public enum Color { RED, GREEN }",
                "demo/Marker.java", "package demo; @java.lang.annotation.Retention("
                        + "java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Marker { String value(); }",
                "demo/Tagged.java", "package demo; @Marker(\"x\") @Deprecated public class Tagged {"
                        + " public static class Inner {} }"));

        ClassFileInfo point = parser.parse(classes.get("demo/Point.class"));
        assertEquals(ClassFileInfo.Kind.RECORD, point.kind());
        assertEquals("java.lang.Record", point.superClassName());
        assertEquals(2, point.recordComponents().size());
        assertEquals("x", point.recordComponents().get(0).name());
        assertEquals("String", point.recordComponents().get(1).renderedType());

        ClassFileInfo shape = parser.parse(classes.get("demo/Shape.class"));
        assertEquals(ClassFileInfo.Kind.INTERFACE, shape.kind());
        assertTrue(shape.sealed());
        assertEquals(java.util.List.of("demo.Circle", "demo.Square"), shape.permittedSubclasses());
        ClassFileInfo.Member area = shape.methods().get(0);
        assertEquals("area", area.name());
        assertFalse(ClassFileInfo.methodAccessNames(area.accessFlags()).contains("abstract"));

        assertEquals(ClassFileInfo.Kind.ENUM, parser.parse(classes.get("demo/Color.class")).kind());
        assertEquals(ClassFileInfo.Kind.ANNOTATION, parser.parse(classes.get("demo/Marker.class")).kind());

        ClassFileInfo tagged = parser.parse(classes.get("demo/Tagged.class"));
        assertTrue(tagged.annotations().contains("demo.Marker"));
        assertTrue(tagged.annotations().contains("java.lang.Deprecated"));
        assertTrue(tagged.deprecated());
        assertEquals(java.util.List.of("demo.Tagged$Inner"), tagged.nestMembers());
        assertEquals("demo.Tagged", parser.parse(classes.get("demo/Tagged$Inner.class")).nestHost());
    }

    @Test
    void parsesGenericSignatureAndLongDoubleConstants() throws Exception {
        byte[] bytes = ClassFixtures.compileOne(temp, 11, "demo/Box.java",
                "package demo; public class Box<T extends Comparable<T>> {"
                        + " public java.util.List<T> items; long big = 123456789012L; double d = 3.25;"
                        + " static final long L1 = 9_000_000_000L; static final double D1 = 2.5e300;"
                        + " public <R> R map(java.util.function.Function<T, R> f) { return null; } }");
        ClassFileInfo info = parser.parse(bytes);

        assertEquals("<T::Ljava/lang/Comparable<TT;>;>Ljava/lang/Object;", info.signature());
        ClassFileInfo.Member items = info.fields().get(0);
        assertEquals("java.util.List", items.renderedType());
        assertEquals("Ljava/util/List<TT;>;", items.signature());
        // 只出现在签名里的类型不会有 Class 常量；父类一定有
        assertTrue(info.referencedClasses().contains("java.lang.Object"));
        assertFalse(info.referencedClasses().contains("demo.Box"));
    }

    @Test
    void parsesModuleInfo() throws Exception {
        Map<String, byte[]> classes = ClassFixtures.compile(temp, 11, Map.of(
                "module-info.java", "module demo.app { requires transitive java.sql; exports demo.api;"
                        + " opens demo.impl to java.base; uses demo.api.Plugin;"
                        + " provides demo.api.Plugin with demo.impl.PluginImpl; }",
                "demo/api/Plugin.java", "package demo.api; public interface Plugin {}",
                "demo/impl/PluginImpl.java", "package demo.impl; public class PluginImpl implements demo.api.Plugin {}"));
        ClassFileInfo info = parser.parse(classes.get("module-info.class"));

        assertEquals(ClassFileInfo.Kind.MODULE, info.kind());
        assertNull(info.superClassName());
        ClassFileInfo.ModuleInfo module = info.module();
        assertNotNull(module);
        assertEquals("demo.app", module.name());
        assertTrue(module.requires().stream().anyMatch(r -> r.module().equals("java.sql") && r.transitive()));
        assertTrue(module.requires().stream().anyMatch(r -> r.module().equals("java.base")));
        assertEquals("demo.api", module.exports().get(0).packageName());
        assertEquals(java.util.List.of("java.base"), module.opens().get(0).targets());
        assertEquals(java.util.List.of("demo.api.Plugin"), module.uses());
        assertEquals(java.util.List.of("demo.impl.PluginImpl"), module.provides().get(0).implementations());
    }

    @Test
    void reportsTruncationWithOffset() throws Exception {
        byte[] bytes = ClassFixtures.compileOne(temp, 8, "demo/T.java", "package demo; public class T {}");
        byte[] cut = Arrays.copyOf(bytes, 40);
        ClassFileException error = assertThrows(ClassFileException.class, () -> parser.parse(cut));
        assertEquals(ClassFileException.Code.TRUNCATED, error.code());
        // 常量池计数（偏移 8）声明的条目在剩余 30 字节里放不下：在计数处就判定截断
        assertEquals(8, error.offset());

        byte[] tail = Arrays.copyOf(bytes, bytes.length - 3);
        ClassFileException late = assertThrows(ClassFileException.class, () -> parser.parse(tail));
        assertEquals(ClassFileException.Code.TRUNCATED, late.code());
        assertTrue(late.offset() > 10 && late.offset() <= tail.length, "offset " + late.offset());

        ClassFileException shortHeader = assertThrows(ClassFileException.class,
                () -> ClassFileParser.parseHeader(new byte[]{(byte) 0xCA, (byte) 0xFE}, 2));
        assertEquals(ClassFileException.Code.TRUNCATED, shortHeader.code());
    }

    @Test
    void rejectsBadMagicUnknownTagAndAbsurdCounts() {
        ClassFileException magic = assertThrows(ClassFileException.class,
                () -> parser.parse(new byte[]{'P', 'K', 3, 4, 0, 0, 0, 52}));
        assertEquals(ClassFileException.Code.BAD_MAGIC, magic.code());
        assertEquals(0, magic.offset());

        // 常量池计数 3，第一个条目的 tag 为 99（不存在）
        byte[] badTag = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 52, 0, 3, 99, 0, 0, 0, 0, 0, 0};
        ClassFileException tag = assertThrows(ClassFileException.class, () -> parser.parse(badTag));
        assertEquals(ClassFileException.Code.BAD_CONSTANT_TAG, tag.code());
        assertEquals(10, tag.offset());

        // 声明 65535 个常量，但后面只有几个字节：不能按计数分配，必须直接拒绝
        byte[] bomb = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 52, (byte) 0xFF, (byte) 0xFF, 1, 0};
        ClassFileException limit = assertThrows(ClassFileException.class, () -> parser.parse(bomb));
        assertEquals(ClassFileException.Code.LIMIT_EXCEEDED, limit.code());

        byte[] oldVersion = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 44};
        assertEquals(ClassFileException.Code.BAD_VERSION,
                assertThrows(ClassFileException.class, () -> parser.parse(oldVersion)).code());
    }

    @Test
    void detectsPreviewMinorVersion() throws Exception {
        byte[] bytes = ClassFixtures.compileOne(temp, 17, "demo/P.java", "package demo; public class P {}");
        bytes[4] = (byte) 0xFF;
        bytes[5] = (byte) 0xFF;
        ClassFileInfo info = parser.parse(bytes);
        assertTrue(info.preview());
        assertEquals("61.65535", info.versionText());
    }

    @ParameterizedTest
    @CsvSource({"45,1.1", "46,1.2", "48,1.4", "49,5", "52,8", "55,11", "61,17", "65,21", "69,25", "44,?"})
    void mapsMajorToRelease(int major, String release) {
        assertEquals(release, ClassFileInfo.releaseName(major));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "(ILjava/lang/String;)V|void (int, String)",
            "()[[J|long[][] ()",
            "(Ljava/util/Map$Entry;ZBCSFD)Ljava/lang/Object;|Object (java.util.Map$Entry, boolean, byte, char, short, float, double)",
            "([Ljava/lang/String;)V|void (String[])",
            "(I|(I",
            "(V)V|(V)V"})
    void rendersMethodDescriptors(String descriptor, String expected) {
        assertEquals(expected, JvmDescriptors.method(descriptor));
    }

    @Test
    void rendersFieldDescriptorsAndNamedMethods() {
        assertEquals("String[]", JvmDescriptors.fieldType("[Ljava/lang/String;"));
        assertEquals("java.lang.reflect.Method", JvmDescriptors.fieldType("Ljava/lang/reflect/Method;"));
        assertEquals("int", JvmDescriptors.fieldType("I"));
        assertEquals("Lbroken", JvmDescriptors.fieldType("Lbroken"));
        assertEquals("void main(String[])", JvmDescriptors.method("main", "([Ljava/lang/String;)V"));
    }
}
