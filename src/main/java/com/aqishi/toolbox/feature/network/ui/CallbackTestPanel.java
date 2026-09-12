package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.util.Json;

import com.aqishi.toolbox.util.JsonFormatter;
import com.aqishi.toolbox.feature.network.application.CallbackMockService;
import com.aqishi.toolbox.feature.network.application.MockRequestRecord;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockResponse;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRule;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRuleResolver;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRuleSet;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRuleValidator;
import com.aqishi.toolbox.feature.network.infra.CallbackMockRuleRepository;
import com.aqishi.toolbox.feature.network.infra.MockHttpRequestParser;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;
import com.aqishi.toolbox.vault.ApplicationPaths;
import com.aqishi.toolbox.vault.AtomicFiles;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableModelEvent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local callback mock server composed from a persistent ordered rule set.
 * UI state is deliberately kept out of the HTTP handler; saving a full
 * immutable snapshot succeeds before it becomes live on the server.
 */
public class CallbackTestPanel extends ToolPanel implements ManagedResourceOwner {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final CallbackMockRuleRepository repository;
    private final CallbackMockService service;
    private final MockRuleValidator validator = new MockRuleValidator();
    private final List<MockRequestRecord> records = new ArrayList<MockRequestRecord>();

    private MockRuleSet ruleSet;
    private String loadWarning;
    private boolean applyingTableModel;

    private JTextField portField;
    private JButton toggleServerButton;
    private JLabel serverStatusLabel;
    private JLabel loadWarningLabel;

    private CallbackMockRuleTableModel ruleTableModel;
    private JTable ruleTable;
    private JButton editRuleButton;
    private JButton deleteRuleButton;
    private JButton moveUpButton;
    private JButton moveDownButton;

    private javax.swing.DefaultListModel<String> requestListModel;
    private JList<String> requestList;
    private JTextArea detailsArea;
    private JTextArea headersArea;
    private JTextArea bodyArea;
    private JTextArea responseArea;

    public CallbackTestPanel() {
        this(defaultRepository(), new CallbackMockService(
                new MockRuleResolver(), new MockHttpRequestParser()));
    }

    CallbackTestPanel(CallbackMockRuleRepository repository,
                      CallbackMockService service) {
        super("dev", "callback.mock",
                "回调", "接口测试", "Mock", "Webhook", "Server", "服务器", "HTTP Mock");
        this.repository = repository;
        this.service = service;
    }

    private static CallbackMockRuleRepository defaultRepository() {
        return new CallbackMockRuleRepository(
                ApplicationPaths.systemDefault().getCallbackMockRulesFile(),
                new AtomicFiles(), Json.mapper());
    }

    @Override
    protected javax.swing.JComponent build() {
        loadRuleSet();
        service.setRequestListener(this::receiveRequest);

        JPanel root = Layouts.page();
        root.add(buildServerArea(), BorderLayout.NORTH);
        root.add(buildWorkspace(), BorderLayout.CENTER);
        return root;
    }

    private javax.swing.JComponent buildServerArea() {
        portField = Fields.text("8080");
        portField.getAccessibleContext().setAccessibleName(t("callback.mock.port"));
        serverStatusLabel = new JLabel(t("callback.mock.stopped"));
        serverStatusLabel.setFont(Tokens.fontBody());

        toggleServerButton = Buttons.primary(t("callback.mock.start"));
        toggleServerButton.addActionListener(event -> toggleServer());

        JPanel portRow = Layouts.box(Tokens.SPACE_MD, 0);
        portRow.add(portField, BorderLayout.WEST);
        portRow.add(serverStatusLabel, BorderLayout.CENTER);

        FormGrid serverForm = new FormGrid();
        serverForm.row(t("callback.mock.port"), portRow);
        Card serverCard = Card.titled(t("callback.mock.server"),
                t("callback.mock.loopback"));
        serverCard.setContent(serverForm);
        serverCard.addHeaderAction(toggleServerButton);

        JPanel area = Layouts.box(0, Tokens.SPACE_SM);
        area.add(serverCard, BorderLayout.NORTH);
        if (loadWarning != null && !loadWarning.trim().isEmpty()) {
            loadWarningLabel = Fields.note(t("callback.mock.loadedWarning", loadWarning));
            area.add(loadWarningLabel, BorderLayout.SOUTH);
        }
        return area;
    }

    private javax.swing.JComponent buildWorkspace() {
        ruleTableModel = new CallbackMockRuleTableModel();
        applyRuleTable(ruleSet.getRules());
        ruleTableModel.addTableModelListener(event -> {
            if (applyingTableModel || event.getType() != TableModelEvent.UPDATE
                    || event.getFirstRow() == TableModelEvent.HEADER_ROW) {
                return;
            }
            if (!persistRuleSet(ruleTableModel.getRules(), ruleSet.getFallbackResponse())) {
                applyRuleTable(ruleSet.getRules());
            }
        });

        ruleTable = new JTable(ruleTableModel);
        ruleTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ruleTable.setFillsViewportHeight(true);
        ruleTable.setRowHeight(Tokens.TABLE_ROW_HEIGHT);
        ruleTable.getSelectionModel().addListSelectionListener(this::updateRuleActions);
        if (ruleTable.getColumnModel().getColumnCount() > 0) {
            ruleTable.getColumnModel().getColumn(0).setMaxWidth(56);
            ruleTable.getColumnModel().getColumn(3).setMaxWidth(72);
        }

        JButton addRuleButton = Buttons.primary(t("callback.mock.add"));
        addRuleButton.addActionListener(event -> openRuleDialog(null, -1));
        JButton editFallbackButton = Buttons.secondary(t("callback.mock.fallback"));
        editFallbackButton.addActionListener(event -> openFallbackDialog());

        editRuleButton = Buttons.secondary(t("callback.mock.edit"));
        editRuleButton.addActionListener(event -> editSelectedRule());
        deleteRuleButton = Buttons.danger(t("callback.mock.delete"));
        deleteRuleButton.addActionListener(event -> deleteSelectedRule());
        moveUpButton = Buttons.secondary(t("callback.mock.moveUp"));
        moveUpButton.addActionListener(event -> moveSelectedRule(-1));
        moveDownButton = Buttons.secondary(t("callback.mock.moveDown"));
        moveDownButton.addActionListener(event -> moveSelectedRule(1));

        Card rulesCard = Card.flush(t("callback.mock.rules"),
                t("callback.mock.ruleOrderHelp"));
        rulesCard.setContent(Fields.scroll(ruleTable));
        rulesCard.addHeaderAction(editFallbackButton);
        rulesCard.addHeaderAction(addRuleButton);
        rulesCard.setFooter(Layouts.wrapRow(Tokens.SPACE_SM, Tokens.SPACE_XS,
                editRuleButton, deleteRuleButton, moveUpButton, moveDownButton));

        requestListModel = new javax.swing.DefaultListModel<String>();
        requestList = new JList<String>(requestListModel);
        requestList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        requestList.setFont(Tokens.fontBody());
        requestList.addListSelectionListener(this::showSelectedRequest);
        JButton clearHistoryButton = Buttons.danger(t("callback.mock.clearHistory"));
        clearHistoryButton.addActionListener(event -> clearRecords());

        Card historyCard = Card.flush(t("callback.mock.history"));
        historyCard.setContent(Fields.scroll(requestList));
        historyCard.addHeaderAction(clearHistoryButton);

        javax.swing.JSplitPane left = Layouts.splitVertical(
                rulesCard, historyCard, 0.62, 0.58);
        javax.swing.JComponent requestDetail = buildRequestDetail();
        updateRuleActions(null);
        return Layouts.splitHorizontal(left, requestDetail, 0.50, 0.46);
    }

    private javax.swing.JComponent buildRequestDetail() {
        javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
        tabs.setBorder(null);
        tabs.setTabLayoutPolicy(javax.swing.JTabbedPane.SCROLL_TAB_LAYOUT);
        detailsArea = Fields.output(12, 42);
        tabs.addTab(t("callback.mock.requestSummary"), Fields.scroll(detailsArea));
        headersArea = Fields.output(12, 42);
        tabs.addTab(t("callback.mock.requestHeaders"), Fields.scroll(headersArea));
        bodyArea = Fields.output(12, 42);
        tabs.addTab(t("callback.mock.requestBody"), Fields.scroll(bodyArea));
        responseArea = Fields.output(12, 42);
        tabs.addTab(t("callback.mock.response"), Fields.scroll(responseArea));
        Card detailCard = Card.flush(t("callback.mock.requestDetail"));
        detailCard.setContent(tabs);
        return detailCard;
    }

    private void loadRuleSet() {
        CallbackMockRuleRepository.LoadResult result = repository.load();
        ruleSet = result.getRuleSet();
        loadWarning = result.getWarning();
        service.replaceRuleSet(ruleSet);
    }

    private void toggleServer() {
        if (service.isRunning()) {
            service.stop();
            updateServerStatus();
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            UIUtils.error(getView(), t("callback.mock.invalidPort"));
            return;
        }
        try {
            service.start(port);
            updateServerStatus();
        } catch (IOException error) {
            UIUtils.error(getView(), t("callback.mock.startFailed", error.getMessage()));
        }
    }

    private void updateServerStatus() {
        if (serverStatusLabel == null || toggleServerButton == null || portField == null) {
            return;
        }
        if (service.isRunning()) {
            int port = service.getPort();
            serverStatusLabel.setText(t("callback.mock.running", port));
            toggleServerButton.setText(t("callback.mock.stop"));
            portField.setEnabled(false);
        } else {
            serverStatusLabel.setText(t("callback.mock.stopped"));
            toggleServerButton.setText(t("callback.mock.start"));
            portField.setEnabled(true);
        }
    }

    private void openRuleDialog(MockRule initialRule, int replacementIndex) {
        Window owner = SwingUtilities.getWindowAncestor(getView());
        CallbackMockRuleDialog dialog = CallbackMockRuleDialog.withSaveHandler(
                owner, initialRule,
                saved -> {
                    List<MockRule> rules = new ArrayList<MockRule>(ruleSet.getRules());
                    if (replacementIndex >= 0 && replacementIndex < rules.size()) {
                        rules.set(replacementIndex, saved);
                    } else {
                        rules.add(saved);
                    }
                    boolean persisted = persistRuleSet(rules, ruleSet.getFallbackResponse());
                    if (persisted) {
                        int selected = replacementIndex >= 0 ? replacementIndex : rules.size() - 1;
                        ruleTable.setRowSelectionInterval(selected, selected);
                    }
                    return persisted;
                });
        dialog.setLocationRelativeTo(owner == null ? getView() : owner);
        dialog.setVisible(true);
    }

    private void editSelectedRule() {
        int row = ruleTable == null ? -1 : ruleTable.getSelectedRow();
        MockRule selected = ruleTableModel == null ? null : ruleTableModel.getRuleAt(row);
        if (selected != null) {
            openRuleDialog(selected, row);
        }
    }

    private void deleteSelectedRule() {
        int row = ruleTable == null ? -1 : ruleTable.getSelectedRow();
        if (deleteRuleAt(row)) {
            int next = Math.min(row, ruleTableModel.getRowCount() - 1);
            if (next >= 0) {
                ruleTable.setRowSelectionInterval(next, next);
            }
        }
    }

    private void moveSelectedRule(int delta) {
        int row = ruleTable == null ? -1 : ruleTable.getSelectedRow();
        int target = row + delta;
        if (moveRule(row, target)) {
            ruleTable.setRowSelectionInterval(target, target);
        }
    }

    private void openFallbackDialog() {
        MockResponse current = ruleSet.getFallbackResponse();
        Window owner = SwingUtilities.getWindowAncestor(getView());
        JDialog dialog = new JDialog(owner, t("callback.mock.fallback"),
                JDialog.ModalityType.APPLICATION_MODAL);
        JSpinner status = Fields.spinner(current.getStatusCode(), 100, 599, 1);
        JTextField contentType = Fields.text(current.getContentType());
        JTextArea body = Fields.area(10, 48);
        body.setText(current.getBody());
        JLabel error = new JLabel(" ");
        error.setFont(Tokens.fontCaption());
        error.setForeground(Tokens.danger());

        FormGrid form = new FormGrid();
        form.rowCompact(t("callback.mock.statusCode"), status);
        form.row(t("callback.mock.contentType"), contentType);
        JPanel responseBody = Layouts.box(0, Tokens.SPACE_XS);
        responseBody.add(Fields.caption(t("callback.mock.responseBody")), BorderLayout.NORTH);
        responseBody.add(Fields.scrollBoxed(body), BorderLayout.CENTER);
        form.fullRow(responseBody);
        Card card = Card.titled(t("callback.mock.fallback"),
                t("callback.mock.defaultResponseHelp"));
        card.setContent(form);

        JButton cancel = Buttons.secondary(t("callback.mock.cancel"));
        cancel.addActionListener(event -> dialog.dispose());
        JButton save = Buttons.primary(t("callback.mock.saveFallback"));
        save.addActionListener(event -> {
            MockResponse replacement = new MockResponse(
                    ((Number) status.getValue()).intValue(), contentType.getText(), body.getText());
            List<String> errors = validator.validate(replacement);
            if (!errors.isEmpty()) {
                error.setText(String.join("；", errors));
                error.setToolTipText(error.getText());
                dialog.revalidate();
                return;
            }
            if (persistRuleSet(ruleSet.getRules(), replacement)) {
                dialog.dispose();
            }
        });

        JPanel actions = Layouts.wrapRow(Tokens.SPACE_SM, Tokens.SPACE_XS,
                error, cancel, save);
        JPanel content = Layouts.box(0, Tokens.SPACE_SM);
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(
                Tokens.SPACE_MD, Tokens.SPACE_MD, Tokens.SPACE_MD, Tokens.SPACE_MD));
        content.add(card, BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setMinimumSize(new Dimension(560, 420));
        dialog.setPreferredSize(new Dimension(680, 560));
        dialog.pack();
        dialog.setLocationRelativeTo(owner == null ? getView() : owner);
        dialog.setVisible(true);
    }

    private boolean persistRuleSet(List<MockRule> rules, MockResponse fallback) {
        MockRuleSet candidate = MockRuleSet.of(rules, fallback);
        List<String> errors = validator.validate(candidate);
        if (!errors.isEmpty()) {
            UIUtils.error(getView(), t("callback.mock.validationFailed",
                    String.join("；", errors)));
            return false;
        }
        try {
            repository.save(candidate);
            ruleSet = candidate;
            service.replaceRuleSet(candidate);
            applyRuleTable(candidate.getRules());
            return true;
        } catch (IOException error) {
            UIUtils.error(getView(), t("callback.mock.saveFailed", error.getMessage()));
            return false;
        }
    }

    private void applyRuleTable(List<MockRule> rules) {
        if (ruleTableModel == null) {
            return;
        }
        applyingTableModel = true;
        try {
            ruleTableModel.setRules(rules);
        } finally {
            applyingTableModel = false;
        }
        updateRuleActions(null);
    }

    private boolean addRule(MockRule rule) {
        if (rule == null) {
            return false;
        }
        List<MockRule> rules = new ArrayList<MockRule>(ruleSet.getRules());
        rules.add(rule);
        return persistRuleSet(rules, ruleSet.getFallbackResponse());
    }

    private boolean deleteRuleAt(int row) {
        if (row < 0 || row >= ruleSet.getRules().size()) {
            return false;
        }
        List<MockRule> rules = new ArrayList<MockRule>(ruleSet.getRules());
        rules.remove(row);
        return persistRuleSet(rules, ruleSet.getFallbackResponse());
    }

    private boolean moveRule(int from, int to) {
        List<MockRule> current = ruleSet.getRules();
        if (from < 0 || to < 0 || from >= current.size() || to >= current.size()
                || from == to) {
            return false;
        }
        List<MockRule> rules = new ArrayList<MockRule>(current);
        Collections.swap(rules, from, to);
        return persistRuleSet(rules, ruleSet.getFallbackResponse());
    }

    private void updateRuleActions(ListSelectionEvent ignored) {
        int row = ruleTable == null ? -1 : ruleTable.getSelectedRow();
        int size = ruleTableModel == null ? 0 : ruleTableModel.getRowCount();
        boolean selected = row >= 0 && row < size;
        if (editRuleButton != null) {
            editRuleButton.setEnabled(selected);
        }
        if (deleteRuleButton != null) {
            deleteRuleButton.setEnabled(selected);
        }
        if (moveUpButton != null) {
            moveUpButton.setEnabled(selected && row > 0);
        }
        if (moveDownButton != null) {
            moveDownButton.setEnabled(selected && row < size - 1);
        }
    }

    private void receiveRequest(MockRequestRecord record) {
        SwingUtilities.invokeLater(() -> appendRequest(record));
    }

    private void appendRequest(MockRequestRecord record) {
        if (record == null || requestListModel == null) {
            return;
        }
        records.add(record);
        String matched = record.isFallback() ? t("callback.mock.fallbackHit")
                : record.getRuleName();
        requestListModel.addElement("[" + TIME_FORMAT.format(record.getReceivedAt()) + "] "
                + record.getMethod() + " " + record.getPath() + " · " + matched);
        requestList.setSelectedIndex(requestListModel.getSize() - 1);
    }

    private void showSelectedRequest(ListSelectionEvent event) {
        if (event.getValueIsAdjusting()) {
            return;
        }
        int index = requestList == null ? -1 : requestList.getSelectedIndex();
        if (index < 0 || index >= records.size()) {
            detailsArea.setText("");
            headersArea.setText("");
            bodyArea.setText("");
            responseArea.setText("");
            return;
        }
        MockRequestRecord record = records.get(index);
        String matched = record.isFallback() ? t("callback.mock.fallbackHit")
                : record.getRuleName();
        StringBuilder summary = new StringBuilder();
        summary.append(t("callback.mock.receivedAt")).append("：")
                .append(TIME_FORMAT.format(record.getReceivedAt())).append('\n');
        summary.append(t("callback.mock.requestMethod")).append("：")
                .append(record.getMethod()).append('\n');
        summary.append(t("callback.mock.requestPath")).append("：")
                .append(record.getPath()).append('\n');
        if (record.getQuery() != null && !record.getQuery().isEmpty()) {
            summary.append(t("callback.mock.query")).append("：")
                    .append(record.getQuery()).append('\n');
        }
        summary.append(t("callback.mock.client")).append("：")
                .append(record.getClientAddress()).append('\n');
        summary.append(t("callback.mock.matchedRule")).append("：")
                .append(matched).append('\n');
        summary.append(t("callback.mock.responseStatus")).append("：")
                .append(record.getResponseStatus()).append('\n');
        detailsArea.setText(summary.toString());
        headersArea.setText(formatHeaders(record));
        bodyArea.setText(prettyBody(record.getBody()));
        responseArea.setText(formatResponse(record));
    }

    private static String formatHeaders(MockRequestRecord record) {
        StringBuilder headers = new StringBuilder();
        for (java.util.Map.Entry<String, List<String>> entry : record.getHeaders().entrySet()) {
            headers.append(entry.getKey()).append(": ");
            headers.append(String.join(", ", entry.getValue())).append('\n');
        }
        return headers.toString();
    }

    private static String prettyBody(String body) {
        String raw = body == null ? "" : body.trim();
        if ((raw.startsWith("{") && raw.endsWith("}"))
                || (raw.startsWith("[") && raw.endsWith("]"))) {
            try {
                return JsonFormatter.pretty(raw);
            } catch (RuntimeException ignored) {
                return raw;
            }
        }
        return raw;
    }

    private static String formatResponse(MockRequestRecord record) {
        StringBuilder response = new StringBuilder();
        response.append(t("callback.mock.responseStatus")).append("：")
                .append(record.getResponseStatus()).append('\n');
        response.append(t("callback.mock.contentType")).append("：")
                .append(record.getResponseContentType() == null
                        ? "" : record.getResponseContentType()).append('\n');
        String body = prettyBody(record.getResponseBody());
        if (!body.isEmpty()) {
            response.append('\n').append(body);
        }
        return response.toString();
    }

    private void clearRecords() {
        records.clear();
        requestListModel.clear();
        detailsArea.setText("");
        headersArea.setText("");
        bodyArea.setText("");
        responseArea.setText("");
    }

    @Override
    public void closeResources() {
        service.closeResources();
    }

    CallbackMockRuleTableModel getRuleTableModelForTest() {
        return ruleTableModel;
    }

    JButton getDeleteRuleButtonForTest() {
        return deleteRuleButton;
    }

    MockResponse getFallbackResponseForTest() {
        return ruleSet.getFallbackResponse();
    }

    String getLoadWarningForTest() {
        return loadWarning;
    }

    boolean addRuleForTest(MockRule rule) {
        return addRule(rule);
    }

    boolean deleteRuleForTest(int row) {
        return deleteRuleAt(row);
    }

    boolean moveRuleForTest(int from, int to) {
        return moveRule(from, to);
    }

    private static String t(String key) {
        return I18n.get(key);
    }

    private static String t(String key, Object... args) {
        return I18n.get(key, args);
    }
}
