package com.aqishi.toolbox.feature.codec.domain;

import com.aqishi.toolbox.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatConversionServiceTest {

    private final FormatConversionService service = new FormatConversionService();

    @Test
    void iniConvertsSectionsAndQuotedValuesToJson() throws Exception {
        String ini = "; application settings\n"
                + "name=java-toolbox\n"
                + "[server]\n"
                + "host=localhost\n"
                + "port=8080\n"
                + "features=[one, \"two,three\"]\n";

        FormatConversionService.ConversionResult result = service.convert(ini, "INI", "JSON");

        assertTrue(result.isSuccess(), result.getErrorMessage());
        JsonNode json = Json.mapper().readTree(result.getOutput());
        assertEquals("java-toolbox", json.get("name").asText());
        assertEquals("localhost", json.at("/server/host").asText());
        assertEquals("8080", json.at("/server/port").asText());
        assertEquals("two,three", json.at("/server/features/1").asText());
    }

    @Test
    void tomlSupportsTablesArraysAndTypedValues() throws Exception {
        String toml = "title = \"TOML Example\"\n"
                + "enabled = true\n"
                + "ports = [8000, 8001]\n"
                + "[owner]\n"
                + "name = \"Tom\"\n"
                + "age = 42\n"
                + "[[products]]\n"
                + "name = \"Hammer\"\n"
                + "price = 10.5\n"
                + "[[products]]\n"
                + "name = \"Nail\"\n";

        FormatConversionService.ConversionResult result = service.convert(toml, "TOML", "JSON");

        assertTrue(result.isSuccess(), result.getErrorMessage());
        JsonNode json = Json.mapper().readTree(result.getOutput());
        assertEquals("TOML Example", json.get("title").asText());
        assertTrue(json.get("enabled").asBoolean());
        assertEquals(8001, json.at("/ports/1").asInt());
        assertEquals("Tom", json.at("/owner/name").asText());
        assertEquals(2, json.get("products").size());
        assertEquals("Nail", json.at("/products/1/name").asText());
    }

    @Test
    void tomlOutputCanBeParsedAgain() {
        String json = "{\"title\":\"demo\",\"owner\":{\"name\":\"Tom\"},\"ports\":[8080,8081]}";

        String toml = service.convertOrThrow(json, "JSON", "TOML");
        Map<?, ?> parsed = (Map<?, ?>) service.parse(toml, "TOML");

        assertEquals("demo", parsed.get("title"));
        assertEquals("Tom", ((Map<?, ?>) parsed.get("owner")).get("name"));
        assertEquals(2, ((java.util.List<?>) parsed.get("ports")).size());
    }

    @Test
    void tomlArrayOfTablesCanBeWrittenAsInlineTables() {
        String source = "[[products]]\nname=\"Hammer\"\n[[products]]\nname=\"Nail\"\n";

        String json = service.convertOrThrow(source, "TOML", "JSON");
        String written = service.convertOrThrow(json, "JSON", "TOML");
        Map<?, ?> reparsed = (Map<?, ?>) service.parse(written, "TOML");

        assertEquals(2, ((java.util.List<?>) reparsed.get("products")).size());
    }

    @Test
    void tomlDatesBecomeStringsInTheJsonCompatibleModel() throws Exception {
        String toml = "created = 2026-09-10T01:20:15Z\n";

        JsonNode json = Json.mapper().readTree(service.convertOrThrow(toml, "TOML", "JSON"));

        assertEquals("2026-09-10T01:20:15Z", json.get("created").asText());
    }

    @Test
    void tomlWriterRejectsNullInsteadOfSilentlyChangingIt() {
        FormatConversionException error = assertThrows(FormatConversionException.class,
                () -> service.convertOrThrow("{\"value\":null}", "JSON", "TOML"));

        assertTrue(error.getMessage().contains("TOML 不支持 null 值"));
    }

    @Test
    void tomlWriterRejectsMixedTypeArrays() {
        FormatConversionException error = assertThrows(FormatConversionException.class,
                () -> service.convertOrThrow("{\"values\":[1,\"two\"]}", "JSON", "TOML"));

        assertTrue(error.getMessage().contains("数组元素类型必须一致"));
    }

    @Test
    void tomlWriterEscapesControlCharacters() {
        String json = "{\"value\":\"before\\u0001\\u0007after\\n\"}";

        String toml = service.convertOrThrow(json, "JSON", "TOML");
        Map<?, ?> parsed = (Map<?, ?>) service.parse(toml, "TOML");

        assertEquals("before\u0001\u0007after\n", parsed.get("value"));
    }

    @Test
    void tomlWriterRejectsIntegersOutsideTomlRange() {
        FormatConversionException error = assertThrows(FormatConversionException.class,
                () -> service.convertOrThrow("{\"value\":9223372036854775808}", "JSON", "TOML"));

        assertTrue(error.getMessage().contains("整数超出 64 位范围"));
    }

    @Test
    void iniWriterRejectsKeysThatWouldBeTrimmedOrParsedAsComments() {
        FormatConversionException leadingComment = assertThrows(FormatConversionException.class,
                () -> service.convertOrThrow("{\"#secret\":\"value\"}", "JSON", "INI"));
        FormatConversionException paddedKey = assertThrows(FormatConversionException.class,
                () -> service.convertOrThrow("{\" key\":\"value\"}", "JSON", "INI"));
        FormatConversionException dottedSection = assertThrows(FormatConversionException.class,
                () -> service.convertOrThrow("{\"a.b\":{\"value\":1}}", "JSON", "INI"));

        assertTrue(leadingComment.getMessage().contains("INI 键包含非法字符"));
        assertTrue(paddedKey.getMessage().contains("INI 键包含非法字符"));
        assertTrue(dottedSection.getMessage().contains("section 键不能包含"));
    }

    @Test
    void iniListsPreserveCommaAndLeadingQuoteValues() {
        String json = "{\"values\":[\"one\",\"two,three\",\"'quoted\"]}";

        String ini = service.convertOrThrow(json, "JSON", "INI");
        Map<?, ?> parsed = (Map<?, ?>) service.parse(ini, "INI");

        assertEquals(List.of("one", "two,three", "'quoted"), parsed.get("values"));
    }

    @Test
    void iniWriterRejectsNestedLists() {
        FormatConversionException error = assertThrows(FormatConversionException.class,
                () -> service.convertOrThrow("{\"values\":[[1,2]]}", "JSON", "INI"));

        assertTrue(error.getMessage().contains("INI 只支持标量列表"));
    }

    @Test
    void syntaxErrorsIncludeFormatAndLocation() {
        FormatConversionService.ConversionResult toml = service.convert("title = \"unterminated", "TOML", "JSON");
        FormatConversionService.ConversionResult ini = service.convert("[server\nhost=localhost", "INI", "JSON");

        assertFalse(toml.isSuccess());
        assertTrue(toml.getErrorMessage().contains("TOML 格式错误"));
        assertTrue(toml.getErrorMessage().contains("第1行"));
        assertFalse(ini.isSuccess());
        assertTrue(ini.getErrorMessage().contains("INI 格式错误"));
    }

    @Test
    void existingJsonYamlPropertiesAndCsvPathsRemainAvailable() throws Exception {
        String json = "{\"name\":\"toolbox\",\"version\":\"1.9.0\"}";

        String yaml = service.convertOrThrow(json, "JSON", "YAML");
        assertEquals("toolbox", Json.mapper().readTree(service.convertOrThrow(yaml, "YAML", "JSON")).get("name").asText());

        String properties = service.convertOrThrow(json, "JSON", "Properties");
        assertTrue(properties.contains("name=toolbox"));
        assertEquals("toolbox", Json.mapper().readTree(service.convertOrThrow(properties, "Properties", "JSON")).get("name").asText());

        String csv = service.convertOrThrow("[{\"name\":\"a,b\",\"value\":1}]", "JSON", "CSV");
        assertTrue(csv.contains("\"a,b\""));
        assertNotNull(service.convertOrThrow(csv, "CSV", "JSON"));

        String xml = service.convertOrThrow(json, "JSON", "XML");
        assertTrue(service.validate(xml, "XML").isValid());
    }

    @Test
    void validationReturnsUsefulResult() {
        assertTrue(service.validate("[server]\nhost=localhost", "INI").isValid());
        FormatConversionService.ValidationResult invalid = service.validate("value = nope", "TOML");
        assertFalse(invalid.isValid());
        assertTrue(invalid.getErrorMessage().contains("TOML 格式错误"));
    }
}
