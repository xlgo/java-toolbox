package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.domain.callbackmock.MatchOperator;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MatchSource;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockCondition;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockResponse;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRule;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRuleValidator;
import com.aqishi.toolbox.feature.network.domain.callbackmock.PathMatchMode;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Independent editor for one callback-mock response rule. */
public final class CallbackMockRuleDialog extends JDialog {
    private static final String[] COMMON_METHODS = {
            "ANY", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"};

    private final MockRule initialRule;
    private final Consumer<MockRule> onSaved;
    private final MockRuleValidator validator = new MockRuleValidator();
    private final List<ConditionEditor> conditionEditors =
            new ArrayList<ConditionEditor>();

    private final JTextField ruleNameField = Fields.text("");
    private final JCheckBox enabledCheck = Fields.check(
            I18n.get("callback.mock.enableRule"), true);
    private final JComboBox<String> methodBox = Fields.combo(COMMON_METHODS, 140);
    private final JComboBox<PathMatchMode> pathModeBox =
            Fields.combo(PathMatchMode.values(), 140);
    private final JTextField pathField = Fields.text("/");
    private final JSpinner statusSpinner = Fields.spinner(200, 100, 599, 1);
    private final JTextField contentTypeField = Fields.text("application/json");
    private final JTextArea responseBodyArea = Fields.area(10, 48);
    private final JPanel conditionsBody = new JPanel();
    private final JLabel validationMessage = new JLabel(" ");

    public CallbackMockRuleDialog(Window owner, MockRule initialRule,
                                  Consumer<MockRule> onSaved) {
        super(owner, initialRule == null ? I18n.get("callback.mock.addRule")
                : I18n.get("callback.mock.editRule"),
                ModalityType.APPLICATION_MODAL);
        this.initialRule = initialRule;
        this.onSaved = onSaved;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        configureControls();
        setContentPane(buildContent());
        populate(initialRule);
        setMinimumSize(new Dimension(680, 560));
        setPreferredSize(new Dimension(840, 700));
        pack();
    }

    private void configureControls() {
        methodBox.setEditable(true);
        pathModeBox.setRenderer(new LocalizedEnumRenderer("callback.mock.pathMode."));
        conditionsBody.setOpaque(false);
        conditionsBody.setLayout(new javax.swing.BoxLayout(
                conditionsBody, javax.swing.BoxLayout.Y_AXIS));
        validationMessage.setForeground(Tokens.danger());
        validationMessage.setFont(Tokens.fontCaption());
        validationMessage.setBorder(BorderFactory.createEmptyBorder(
                Tokens.SPACE_XS, 0, 0, 0));
        ruleNameField.getAccessibleContext().setAccessibleName(t("callback.mock.ruleName"));
        methodBox.getAccessibleContext().setAccessibleName(t("callback.mock.method"));
        pathField.getAccessibleContext().setAccessibleName(t("callback.mock.path"));
        contentTypeField.getAccessibleContext().setAccessibleName(t("callback.mock.contentType"));
        responseBodyArea.getAccessibleContext().setAccessibleName(t("callback.mock.responseBody"));
    }

    private JPanel buildContent() {
        FormGrid matchForm = new FormGrid();
        matchForm.row(t("callback.mock.ruleName"), ruleNameField);
        matchForm.rowCompact(t("callback.mock.method"), methodBox);
        matchForm.rowCompact(t("callback.mock.pathMode"), pathModeBox);
        matchForm.row(t("callback.mock.path"), pathField);
        matchForm.fullRow(enabledCheck);
        Card matchCard = Card.titled(t("callback.mock.requestMatching"));
        matchCard.setContent(matchForm);

        JButton addCondition = Buttons.secondary(t("callback.mock.addCondition"));
        addCondition.addActionListener(event -> addCondition(null));
        Card conditionCard = Card.titled(t("callback.mock.condition"),
                t("callback.mock.allConditions"));
        conditionCard.addHeaderAction(addCondition);
        JScrollPane conditionScroll = Fields.scrollVertical(conditionsBody);
        conditionScroll.setPreferredSize(new Dimension(560, 140));
        conditionCard.setContent(conditionScroll);

        FormGrid responseForm = new FormGrid();
        responseForm.rowCompact(t("callback.mock.statusCode"), statusSpinner);
        responseForm.row(t("callback.mock.contentType"), contentTypeField);
        responseForm.fullRow(Fields.caption(
                t("callback.mock.templateHelp")));
        JPanel responseBody = Layouts.box(0, Tokens.SPACE_XS);
        responseBody.add(Fields.caption(t("callback.mock.responseBody")), BorderLayout.NORTH);
        responseBody.add(Fields.scrollBoxed(responseBodyArea), BorderLayout.CENTER);
        responseForm.fullRow(responseBody);
        Card responseCard = Card.titled(t("callback.mock.response"));
        responseCard.setContent(responseForm);

        JPanel form = Layouts.stack(Tokens.SPACE_MD, matchCard, conditionCard, responseCard);
        JScrollPane scroll = Fields.scrollVertical(form);

        JButton cancel = Buttons.secondary(t("callback.mock.cancel"));
        cancel.addActionListener(event -> dispose());
        JButton save = Buttons.primary(t("callback.mock.saveRule"));
        save.addActionListener(event -> saveRule());
        JPanel actions = Layouts.wrapRow(Tokens.SPACE_SM, Tokens.SPACE_XS,
                validationMessage, cancel, save);

        JPanel content = Layouts.box(0, Tokens.SPACE_SM);
        content.setBorder(BorderFactory.createEmptyBorder(
                Tokens.SPACE_MD, Tokens.SPACE_MD, Tokens.SPACE_MD, Tokens.SPACE_MD));
        content.add(scroll, BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);
        return content;
    }

    private void populate(MockRule rule) {
        if (rule == null) {
            responseBodyArea.setText("{\n  \"status\": \"success\"\n}");
            refreshConditions();
            return;
        }
        ruleNameField.setText(rule.getName());
        enabledCheck.setSelected(rule.isEnabled());
        methodBox.setSelectedItem(rule.getMethod());
        pathModeBox.setSelectedItem(rule.getPathMode());
        pathField.setText(rule.getPath());
        if (rule.getResponse() != null) {
            statusSpinner.setValue(Math.max(100, Math.min(599,
                    rule.getResponse().getStatusCode())));
            contentTypeField.setText(rule.getResponse().getContentType());
            responseBodyArea.setText(rule.getResponse().getBody());
        }
        for (MockCondition condition : rule.getConditions()) {
            addCondition(condition);
        }
        refreshConditions();
    }

    private void addCondition(MockCondition condition) {
        conditionEditors.add(new ConditionEditor(condition));
        refreshConditions();
    }

    private void refreshConditions() {
        conditionsBody.removeAll();
        if (conditionEditors.isEmpty()) {
            conditionsBody.add(Fields.caption(t("callback.mock.noConditions")));
        } else {
            for (ConditionEditor editor : conditionEditors) {
                conditionsBody.add(editor.getView());
                conditionsBody.add(javax.swing.Box.createVerticalStrut(Tokens.SPACE_XS));
            }
        }
        conditionsBody.revalidate();
        conditionsBody.repaint();
    }

    private boolean saveRule() {
        MockRule candidate = buildRule();
        List<String> errors = validator.validate(candidate);
        if (!errors.isEmpty()) {
            validationMessage.setText(String.join("；", errors));
            validationMessage.setToolTipText(validationMessage.getText());
            revalidate();
            return false;
        }
        if (onSaved != null) {
            onSaved.accept(candidate);
        }
        dispose();
        return true;
    }

    private MockRule buildRule() {
        Object methodValue = methodBox.isEditable()
                ? methodBox.getEditor().getItem() : methodBox.getSelectedItem();
        MockRule.Builder builder = MockRule.builder(ruleNameField.getText())
                .enabled(enabledCheck.isSelected())
                .method(methodValue == null ? "" : methodValue.toString())
                .path((PathMatchMode) pathModeBox.getSelectedItem(), pathField.getText())
                .response(new MockResponse(((Number) statusSpinner.getValue()).intValue(),
                        contentTypeField.getText(), responseBodyArea.getText()));
        if (initialRule != null) {
            builder.id(initialRule.getId());
        }
        for (ConditionEditor editor : conditionEditors) {
            builder.condition(editor.toCondition());
        }
        return builder.build();
    }

    MockRule getInitialRuleForTest() {
        return initialRule;
    }

    JTextField getExpectedValueFieldForTest() {
        return conditionEditors.isEmpty() ? null : conditionEditors.get(0).expectedField;
    }

    JTextField getRuleNameFieldForTest() {
        return ruleNameField;
    }

    String getValidationMessageForTest() {
        return validationMessage.getText();
    }

    boolean saveForTest() {
        return saveRule();
    }

    private final class ConditionEditor {
        private final JComboBox<MatchSource> sourceBox =
                Fields.combo(MatchSource.values(), 110);
        private final JComboBox<MatchOperator> operatorBox =
                Fields.combo(MatchOperator.values(), 110);
        private final JTextField fieldField = Fields.text("");
        private final JTextField expectedField = Fields.text("");
        private final JPanel view;

        private ConditionEditor(MockCondition initial) {
            sourceBox.setRenderer(new LocalizedEnumRenderer("callback.mock.source."));
            operatorBox.setRenderer(new LocalizedEnumRenderer("callback.mock.operator."));
            sourceBox.setSelectedItem(initial == null || initial.getSource() == null
                    ? MatchSource.QUERY : initial.getSource());
            operatorBox.setSelectedItem(initial == null || initial.getOperator() == null
                    ? MatchOperator.EQUALS : initial.getOperator());
            fieldField.setText(initial == null ? "" : initial.getField());
            expectedField.setText(initial == null || initial.getExpected() == null
                    ? "" : initial.getExpected());
            sourceBox.getAccessibleContext().setAccessibleName(t("callback.mock.conditionSource"));
            operatorBox.getAccessibleContext().setAccessibleName(t("callback.mock.conditionOperator"));
            fieldField.getAccessibleContext().setAccessibleName(t("callback.mock.conditionField"));
            expectedField.getAccessibleContext().setAccessibleName(t("callback.mock.conditionExpected"));
            operatorBox.addActionListener(event -> updateExpectedState());
            JButton remove = Buttons.compact("×");
            remove.setToolTipText(t("callback.mock.deleteCondition"));
            remove.getAccessibleContext().setAccessibleName(t("callback.mock.deleteCondition"));
            remove.addActionListener(event -> {
                conditionEditors.remove(ConditionEditor.this);
                refreshConditions();
            });
            view = Layouts.wrapRow(Tokens.SPACE_SM, Tokens.SPACE_XS,
                    sourceBox, fieldField, operatorBox, expectedField, remove);
            updateExpectedState();
        }

        private JPanel getView() {
            return view;
        }

        private void updateExpectedState() {
            boolean needsExpected = operatorBox.getSelectedItem() != MatchOperator.EXISTS;
            expectedField.setEnabled(needsExpected);
        }

        private MockCondition toCondition() {
            MatchOperator operator = (MatchOperator) operatorBox.getSelectedItem();
            return new MockCondition((MatchSource) sourceBox.getSelectedItem(),
                    fieldField.getText(), operator,
                    operator == MatchOperator.EXISTS ? null : expectedField.getText());
        }
    }

    private static String t(String key) {
        return I18n.get(key);
    }

    private static final class LocalizedEnumRenderer extends DefaultListCellRenderer {
        private final String keyPrefix;

        private LocalizedEnumRenderer(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                                                      int index, boolean selected,
                                                      boolean hasFocus) {
            Component component = super.getListCellRendererComponent(
                    list, value, index, selected, hasFocus);
            if (value instanceof Enum<?>) {
                setText(I18n.get(keyPrefix + ((Enum<?>) value).name()
                        .toLowerCase(Locale.ROOT)));
            }
            return component;
        }
    }
}
