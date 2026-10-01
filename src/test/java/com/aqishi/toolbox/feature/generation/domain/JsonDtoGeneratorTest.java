package com.aqishi.toolbox.feature.generation.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.*;
import java.util.*;

class JsonDtoGeneratorTest {
    @TempDir Path temp;
    private final JsonDtoGenerator generator = new JsonDtoGenerator();

    @Test
    void recordNamesCannotCollideWithObjectMethods() throws Exception {
        String source =
                generator.generate(
                        "{\"hashCode\":1,\"toString\":\"x\",\"wait\":true,\"getClass\":{},\"notify\":1}",
                        new JsonDtoGenerator.Options(
                                "", "Dto", JsonDtoGenerator.Style.RECORD, true, Map.of()));
        Path file = temp.resolve("Dto.java");
        Files.writeString(file, source);
        var diagnostics = new java.io.ByteArrayOutputStream();
        int result =
                javax.tools.ToolProvider.getSystemJavaCompiler()
                        .run(
                                null,
                                null,
                                diagnostics,
                                "--release",
                                "17",
                                "-classpath",
                                System.getProperty("java.class.path"),
                                "-d",
                                temp.toString(),
                                file.toString());
        assertEquals(0, result, diagnostics.toString());
    }

    @ParameterizedTest
    @EnumSource(
            value = JsonDtoGenerator.Style.class,
            names = {"BEAN", "RECORD"})
    void generatedNestedCodeCompiles(JsonDtoGenerator.Style style) throws Exception {
        String input =
                "{\"request-id\":\"x\",\"class\":1,\"a-b\":2,\"a"
                    + " b\":3,\"users\":[{\"id\":1,\"address\":{\"zip\":\"001\"}},{\"id\":2147483648,\"active\":true}],\"mixed\":[1,\"a\"],\"empty\":[]}";
        String source =
                generator.generate(
                        input,
                        new JsonDtoGenerator.Options(
                                "sample",
                                "ResponseDto",
                                style,
                                true,
                                Map.of("request-id", "requestId")));
        assertTrue(source.contains("java.lang.Long id"));
        assertTrue(source.contains("java.util.List<java.lang.Object> mixed"));
        assertTrue(source.contains("classValue"));
        Path file = temp.resolve("ResponseDto.java");
        Files.writeString(file, source);
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        var diagnostics = new java.io.ByteArrayOutputStream();
        int code =
                compiler.run(
                        null,
                        null,
                        diagnostics,
                        "--release",
                        "17",
                        "-encoding",
                        "UTF-8",
                        "-classpath",
                        System.getProperty("java.class.path"),
                        "-d",
                        temp.toString(),
                        file.toString());
        assertEquals(0, code, diagnostics.toString());
    }

    @Test
    void rootObjectArrayMergesMissingAndNullFields() throws Exception {
        String result =
                generator.generate(
                        "[{\"a\":null},{\"a\":12,\"b\":true}]",
                        new JsonDtoGenerator.Options(
                                "", "Dto", JsonDtoGenerator.Style.BEAN, false, Map.of()));
        assertTrue(result.contains("java.lang.Integer a"));
        assertTrue(result.contains("java.lang.Boolean b"));
    }

    @Test
    void rejectsInvalidIdentifiersAndScalarRoots() {
        assertThrows(
                Exception.class,
                () ->
                        generator.generate(
                                "{}",
                                new JsonDtoGenerator.Options(
                                        "",
                                        "class",
                                        JsonDtoGenerator.Style.BEAN,
                                        false,
                                        Map.of())));
        assertThrows(
                Exception.class,
                () ->
                        generator.generate(
                                "123",
                                new JsonDtoGenerator.Options(
                                        "", "Dto", JsonDtoGenerator.Style.BEAN, false, Map.of())));
    }

    @Test
    void lombokStyleIsExplicit() throws Exception {
        String source =
                generator.generate(
                        "{\"child\":{\"x\":1}}",
                        new JsonDtoGenerator.Options(
                                "", "Dto", JsonDtoGenerator.Style.LOMBOK, false, Map.of()));
        assertTrue(source.contains("@lombok.Data"));
        assertFalse(source.contains("getChild"));
    }
}
