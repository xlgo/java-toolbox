package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.application.CallbackMockService;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockResponse;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRule;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRuleResolver;
import com.aqishi.toolbox.feature.network.infra.CallbackMockRuleRepository;
import com.aqishi.toolbox.feature.network.infra.MockHttpRequestParser;
import com.aqishi.toolbox.util.ConfigManagerTestSupport;
import com.aqishi.toolbox.vault.ApplicationPaths;
import com.aqishi.toolbox.vault.AtomicFiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallbackTestPanelTest {
    @TempDir
    Path temp;

    @Test
    void editsOrderedRulesProtectsFallbackAndClosesServiceIdempotently()
            throws Exception {
        CallbackMockService service = service();
        CallbackTestPanel panel = build(repository(temp.resolve("rules.json")), service);
        try {
            assertEquals("callback.mock", panel.getName());
            assertEquals(0, panel.getRuleTableModelForTest().getRowCount());
            assertFalse(panel.getDeleteRuleButtonForTest().isEnabled());

            MockRule first = MockRule.builder("first")
                    .response(new MockResponse(201, "application/json", "first"))
                    .build();
            MockRule second = MockRule.builder("second")
                    .response(new MockResponse(202, "application/json", "second"))
                    .build();
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(panel.addRuleForTest(first));
                assertTrue(panel.addRuleForTest(second));
                assertTrue(panel.moveRuleForTest(1, 0));
                assertTrue(panel.deleteRuleForTest(1));
            });

            assertEquals(1, panel.getRuleTableModelForTest().getRowCount());
            assertEquals("second", panel.getRuleTableModelForTest().getRuleAt(0).getName());
            assertEquals("second", service.getRuleSet().getRules().get(0).getName());
            assertEquals(200, panel.getFallbackResponseForTest().getStatusCode());

            service.start(0);
            assertTrue(service.isRunning());
            panel.closeResources();
            panel.closeResources();
            assertFalse(service.isRunning());
        } finally {
            panel.closeResources();
        }
    }

    @Test
    void showsNonfatalLoadWarningForMalformedPersistedRules() throws Exception {
        Path file = temp.resolve("malformed.json");
        Files.write(file, "{not-json".getBytes(StandardCharsets.UTF_8));
        CallbackTestPanel panel = build(repository(file), service());
        try {
            assertNotNull(panel.getLoadWarningForTest());
            assertFalse(panel.getLoadWarningForTest().trim().isEmpty());
            assertEquals(0, panel.getRuleTableModelForTest().getRowCount());
        } finally {
            panel.closeResources();
        }
    }

    private CallbackTestPanel build(CallbackMockRuleRepository repository,
                                    CallbackMockService service) throws Exception {
        Map<String, String> environment = new HashMap<String, String>();
        environment.put("APPDATA", temp.toString());
        ApplicationPaths paths = ApplicationPaths.resolve("Windows 11", temp.toString(),
                environment, temp);
        AutoCloseable configuration = ConfigManagerTestSupport.install(paths);
        AtomicReference<CallbackTestPanel> reference =
                new AtomicReference<CallbackTestPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                CallbackTestPanel panel = new CallbackTestPanel(repository, service);
                panel.getView();
                reference.set(panel);
            });
            return reference.get();
        } finally {
            // CallbackTestPanel has all of the state it needs after construction.
            configuration.close();
        }
    }

    private CallbackMockRuleRepository repository(Path file) {
        return new CallbackMockRuleRepository(file, new AtomicFiles(),
                new ObjectMapper());
    }

    private CallbackMockService service() {
        return new CallbackMockService(new MockRuleResolver(),
                new MockHttpRequestParser());
    }
}
