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

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
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
    private final JCheckBox enabledCheck = Fields.check("启用此规则", true);
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
        super(owner, initialRule == null ? "新增响应规则" : "编辑响应规则",
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
        conditionsBody.setOpaque(false);
        conditionsBody.setLayout(new javax.swing.BoxLayout(
                conditionsBody, javax.swing.BoxLayout.Y_AXIS));
        validationMessage.setForeground(Tokens.danger());
        validationMessage.setFont(Tokens.fontCaption());
        validationMessage.setBorder(BorderFactory.createEmptyBorder(
                Tokens.SPACE_XS, 0, 0, 0));
        ruleNameField.getAccessibleContext().setAccessibleName("规则名称");
        methodBox.getAccessibleContext().setAccessibleName("请求方法");
        pathField.getAccessibleContext().setAccessibleName("请求路径");
        contentTypeField.getAccessibleContext().setAccessibleName("响应 Content-Type");
        responseBodyArea.getAccessibleContext().setAccessibleName("响应报文");
    }

    private JPanel buildContent() {
        FormGrid matchForm = new FormGrid();
        matchForm.row("规则名称", ruleNameField);
        matchForm.rowCompact("请求方法", methodBox);
        matchForm.rowCompact("路径方式", pathModeBox);
        matchForm.row("路径", pathField);
        matchForm.fullRow(enabledCheck);
        Card matchCard = Card.titled("请求匹配");
        matchCard.setContent(matchForm);

        JButton addCondition = Buttons.secondary("添加条件");
        addCondition.addActionListener(event -> addCondition(null));
        Card conditionCard = Card.titled("匹配条件", "所有条件均需满足");
        conditionCard.addHeaderAction(addCondition);
        JScrollPane conditionScroll = Fields.scrollVertical(conditionsBody);
        conditionScroll.setPreferredSize(new Dimension(560, 140));
        conditionCard.setContent(conditionScroll);

        FormGrid responseForm = new FormGrid();
        responseForm.rowCompact("状态码", statusSpinner);
        responseForm.row("Content-Type", contentTypeField);
        responseForm.fullRow(Fields.caption(
                "可用变量：${query.name}、${header.Name}、${form.name}、${json.path}"));
        JPanel responseBody = Layouts.box(0, Tokens.SPACE_XS);
        responseBody.add(Fields.caption("响应报文"), BorderLayout.NORTH);
        responseBody.add(Fields.scrollBoxed(responseBodyArea), BorderLayout.CENTER);
        responseForm.fullRow(responseBody);
        Card responseCard = Card.titled("响应");
        responseCard.setContent(responseForm);

        JPanel form = Layouts.stack(Tokens.SPACE_MD, matchCard, conditionCard, responseCard);
        JScrollPane scroll = Fields.scrollVertical(form);

        JButton cancel = Buttons.secondary("取消");
        cancel.addActionListener(event -> dispose());
        JButton save = Buttons.primary("保存规则");
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
            conditionsBody.add(Fields.caption("未配置附加条件，匹配方法和路径即可响应。"));
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
            sourceBox.setSelectedItem(initial == null || initial.getSource() == null
                    ? MatchSource.QUERY : initial.getSource());
            operatorBox.setSelectedItem(initial == null || initial.getOperator() == null
                    ? MatchOperator.EQUALS : initial.getOperator());
            fieldField.setText(initial == null ? "" : initial.getField());
            expectedField.setText(initial == null || initial.getExpected() == null
                    ? "" : initial.getExpected());
            sourceBox.getAccessibleContext().setAccessibleName("条件来源");
            operatorBox.getAccessibleContext().setAccessibleName("条件操作符");
            fieldField.getAccessibleContext().setAccessibleName("条件字段");
            expectedField.getAccessibleContext().setAccessibleName("条件期望值");
            operatorBox.addActionListener(event -> updateExpectedState());
            JButton remove = Buttons.compact("×");
            remove.setToolTipText("删除条件");
            remove.getAccessibleContext().setAccessibleName("删除条件");
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
}
