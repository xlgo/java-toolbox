package com.aqishi.toolbox.feature.network.infra;

import com.aqishi.toolbox.feature.network.domain.callbackmock.MatchOperator;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MatchSource;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockCondition;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockResponse;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRule;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRuleSet;
import com.aqishi.toolbox.feature.network.domain.callbackmock.PathMatchMode;
import com.aqishi.toolbox.vault.AtomicFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallbackMockRuleRepositoryTest {
    @TempDir
    Path temp;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void returnsBuiltInDefaultsWhenRuleFileIsAbsent() {
        CallbackMockRuleRepository repository = repository(new AtomicFiles());

        CallbackMockRuleRepository.LoadResult result = repository.load();

        assertEquals(MockRuleSet.CURRENT_VERSION, result.getRuleSet().getVersion());
        assertTrue(result.getRuleSet().getRules().isEmpty());
        assertEquals(new MockResponse(200, "application/json", defaultBody()),
                result.getRuleSet().getFallbackResponse());
        assertNull(result.getWarning());
    }

    @Test
    void savesAndLoadsRulesInListOrderWithLongResponseBody() throws Exception {
        String longBody = repeated("response-payload-", 12_000);
        MockRule first = MockRule.builder("first")
                .id("first-rule")
                .method("POST")
                .path(PathMatchMode.PREFIX, "/payments")
                .condition(new MockCondition(MatchSource.JSON, "status",
                        MatchOperator.EQUALS, "paid"))
                .response(new MockResponse(201, "application/json", longBody))
                .build();
        MockRule second = MockRule.builder("second")
                .id("second-rule")
                .enabled(false)
                .method("GET")
                .path(PathMatchMode.REGEX, "/orders/[0-9]+")
                .condition(new MockCondition(MatchSource.HEADER, "X-Mode",
                        MatchOperator.CONTAINS, "preview"))
                .response(new MockResponse(202, "text/plain", "second response"))
                .build();
        MockRuleSet expected = MockRuleSet.of(Arrays.asList(first, second),
                new MockResponse(299, "application/json", "fallback"));
        CallbackMockRuleRepository repository = repository(new AtomicFiles());

        repository.save(expected);

        CallbackMockRuleRepository.LoadResult loaded = repository.load();
        JsonNode root = mapper.readTree(Files.readAllBytes(file()));
        assertEquals(MockRuleSet.CURRENT_VERSION, root.get("version").asInt());
        assertEquals("first-rule", root.get("rules").get(0).get("id").asText());
        assertEquals("second-rule", root.get("rules").get(1).get("id").asText());
        assertEquals(expected, loaded.getRuleSet());
        assertNull(loaded.getWarning());
    }

    @Test
    void malformedJsonRetainsSourceAndReturnsDefaultsWithWarning() throws Exception {
        byte[] malformed = "{not valid json".getBytes(StandardCharsets.UTF_8);
        Files.write(file(), malformed);

        CallbackMockRuleRepository.LoadResult result = repository(new AtomicFiles()).load();

        assertTrue(result.getRuleSet().getRules().isEmpty());
        assertEquals(new MockResponse(200, "application/json", defaultBody()),
                result.getRuleSet().getFallbackResponse());
        assertNotNull(result.getWarning());
        assertFalse(result.getWarning().trim().isEmpty());
        assertArrayEquals(malformed, Files.readAllBytes(file()));
    }

    @Test
    void unknownVersionRetainsSourceAndReturnsDefaultsWithWarning() throws Exception {
        byte[] unsupported = ("{\"version\":999,\"rules\":[],\"fallbackResponse\":"
                + "{\"statusCode\":200,\"contentType\":\"application/json\",\"body\":\"old\"}}")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file(), unsupported);

        CallbackMockRuleRepository.LoadResult result = repository(new AtomicFiles()).load();

        assertTrue(result.getRuleSet().getRules().isEmpty());
        assertEquals(new MockResponse(200, "application/json", defaultBody()),
                result.getRuleSet().getFallbackResponse());
        assertNotNull(result.getWarning());
        assertTrue(result.getWarning().toLowerCase().contains("version"));
        assertArrayEquals(unsupported, Files.readAllBytes(file()));
    }

    @Test
    void writeFailureLeavesExistingRuleFileAndLoadedRulesUnchanged() throws Exception {
        MockRuleSet original = MockRuleSet.of(Arrays.asList(MockRule.builder("original")
                        .id("original-rule")
                        .response(new MockResponse(200, "application/json", "original"))
                        .build()),
                new MockResponse(200, "application/json", "fallback"));
        repository(new AtomicFiles()).save(original);
        byte[] before = Files.readAllBytes(file());
        CallbackMockRuleRepository failingRepository = repository(new AtomicFiles() {
            @Override
            public void write(Path target, byte[] bytes) throws IOException {
                throw new IOException("injected write failure");
            }
        });
        MockRuleSet replacement = MockRuleSet.of(Arrays.asList(MockRule.builder("replacement")
                        .id("replacement-rule")
                        .response(new MockResponse(201, "application/json", "replacement"))
                        .build()),
                new MockResponse(201, "application/json", "replacement fallback"));

        assertThrows(IOException.class, () -> failingRepository.save(replacement));

        assertArrayEquals(before, Files.readAllBytes(file()));
        assertEquals(original, repository(new AtomicFiles()).load().getRuleSet());
    }

    private CallbackMockRuleRepository repository(AtomicFiles atomicFiles) {
        return new CallbackMockRuleRepository(file(), atomicFiles, mapper);
    }

    private Path file() {
        return temp.resolve("callback-mock-rules.json");
    }

    private static String defaultBody() {
        return "{\n  \"status\": \"success\",\n  \"message\": \"Callback received\"\n}";
    }

    private static String repeated(String value, int count) {
        StringBuilder body = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            body.append(value);
        }
        return body.toString();
    }
}
