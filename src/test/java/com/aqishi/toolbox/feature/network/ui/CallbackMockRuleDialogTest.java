package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.domain.callbackmock.MatchOperator;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MatchSource;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockCondition;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockResponse;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRule;
import com.aqishi.toolbox.feature.network.domain.callbackmock.PathMatchMode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallbackMockRuleDialogTest {
    @Test
    void tableSummarizesRulesAndOnlyAllowsEnabledToBeEdited() {
        MockRule rule = originalRule();
        CallbackMockRuleTableModel model = new CallbackMockRuleTableModel();

        model.setRules(Arrays.asList(rule));

        assertEquals(1, model.getRowCount());
        assertEquals("original", model.getValueAt(0, 1));
        assertTrue(model.conditionSummary(rule).contains("QUERY.status"));
        assertTrue(model.isCellEditable(0, 0));
        assertFalse(model.isCellEditable(0, 1));
        model.setValueAt(Boolean.FALSE, 0, 0);
        assertFalse(model.getRuleAt(0).isEnabled());
    }

    @Test
    void existsConditionDisablesExpectedValueAndCancelKeepsOriginalRule()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<CallbackMockRuleDialog> ref =
                new AtomicReference<CallbackMockRuleDialog>();
        AtomicReference<MockRule> saved = new AtomicReference<MockRule>();
        SwingUtilities.invokeAndWait(() -> ref.set(new CallbackMockRuleDialog(
                null, originalRule(), saved::set)));

        CallbackMockRuleDialog dialog = ref.get();
        assertFalse(dialog.getExpectedValueFieldForTest().isEnabled());
        assertEquals("original", dialog.getInitialRuleForTest().getName());
        assertNull(saved.get());
        SwingUtilities.invokeAndWait(dialog::dispose);
        assertNull(saved.get());
    }

    @Test
    void invalidFieldsShowLocalValidationMessageBeforeSaving() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<CallbackMockRuleDialog> ref =
                new AtomicReference<CallbackMockRuleDialog>();
        SwingUtilities.invokeAndWait(() -> ref.set(new CallbackMockRuleDialog(
                null, null, null)));

        CallbackMockRuleDialog dialog = ref.get();
        SwingUtilities.invokeAndWait(() -> {
            dialog.getRuleNameFieldForTest().setText(" ");
            assertFalse(dialog.saveForTest());
        });
        assertNotNull(dialog.getValidationMessageForTest());
        assertTrue(dialog.getValidationMessageForTest().contains("name"));
        SwingUtilities.invokeAndWait(dialog::dispose);
    }

    private static MockRule originalRule() {
        return MockRule.builder("original")
                .id("original-id")
                .method("POST")
                .path(PathMatchMode.PREFIX, "/orders")
                .condition(new MockCondition(MatchSource.QUERY, "status",
                        MatchOperator.EXISTS, null))
                .response(new MockResponse(201, "application/json", "{}"))
                .build();
    }
}
