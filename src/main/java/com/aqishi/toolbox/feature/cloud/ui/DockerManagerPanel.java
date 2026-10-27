package com.aqishi.toolbox.feature.cloud.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.cloud.application.DockerService;
import com.aqishi.toolbox.feature.cloud.application.DockerService.*;
import com.aqishi.toolbox.feature.network.ssh.domain.SshConnectionConfig;
import com.aqishi.toolbox.feature.network.ssh.infra.SshConfigStore;
import com.aqishi.toolbox.feature.network.ssh.ui.SshHostKeyPrompts;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.infra.docker.DockerConnectionOptions;
import com.aqishi.toolbox.infra.docker.DockerConnectionOptions.SshMode;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.*;
import com.fasterxml.jackson.databind.JsonNode;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;

/** Local Docker Desktop/Engine and remote Docker via saved SSH profiles. */
public final class DockerManagerPanel extends ToolPanel implements ManagedResourceOwner {
    private JTextField executableField, contextField, filterField;
    private JTextField tunnelHostField;
    private JSpinner tunnelPortSpinner;
    private JComboBox<SshMode> sshModeCombo;
    private JPanel tunnelSettings;
    private JSpinner timeoutSpinner, tailSpinner;
    private JCheckBox useSshCheck, allCheck;
    private JComboBox<SshConnectionConfig> sshCombo;
    private JComboBox<Kind> kindCombo;
    private JButton connectBtn,
            disconnectBtn,
            cancelBtn,
            settingsBtn,
            refreshBtn,
            inspectBtn,
            logsBtn,
            statsBtn,
            pullBtn,
            createBtn,
            deleteBtn;
    private final Map<String, JButton> actions = new LinkedHashMap<>();
    private JPanel settings;
    private JLabel statusLabel;
    private JTable table;
    private DefaultTableModel model;
    private JTextArea output;
    private List<JsonNode> rows = List.of();
    private volatile DockerService client;
    private volatile boolean closed;
    private boolean busy;
    private long epoch;
    private final Runnable sshChanges = this::refreshSshConfigs;

    @FunctionalInterface
    interface ClientFactory {
        DockerService create(DockerConnectionOptions options, int timeout);
    }

    private final ClientFactory factory;

    public DockerManagerPanel() {
        this(null);
    }

    DockerManagerPanel(ClientFactory factory) {
        super(ToolCatalog.DOCKER_MANAGEMENT);
        this.factory =
                factory == null
                        ? (options, timeout) ->
                                new DockerService(
                                        options.createRunner(SshHostKeyPrompts.dialogs(getView())),
                                        timeout)
                        : factory;
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();
        executableField = Fields.text("docker");
        contextField = Fields.text("");
        timeoutSpinner = Fields.spinner(60, 1, 600, 10);
        useSshCheck = Fields.check(t("ssh"), false);
        sshCombo = Fields.combo(new SshConnectionConfig[0]);
        sshModeCombo = Fields.combo(SshMode.values());
        sshModeCombo.addActionListener(
                e -> {
                    if (kindCombo != null) updateActions();
                });
        tunnelHostField = Fields.text("127.0.0.1");
        tunnelPortSpinner = Fields.spinner(2375, 1, 65535, 1);
        FormGrid tunnelForm = new FormGrid();
        JPanel target = Layouts.box(Tokens.SPACE_SM, 0);
        target.add(tunnelHostField, BorderLayout.CENTER);
        target.add(
                Layouts.wrapRow(Fields.caption(t("ssh.remotePort")), tunnelPortSpinner),
                BorderLayout.EAST);
        tunnelForm.row(t("ssh.remoteHost"), target);
        tunnelForm.fullRow(Fields.note(t("ssh.tunnelHint")));
        tunnelSettings = Layouts.box(0, 0);
        tunnelSettings.add(tunnelForm);
        tunnelSettings.setVisible(false);
        connectBtn = button("connect", this::connect);
        disconnectBtn = button("disconnect", this::disconnect);
        cancelBtn = button("cancel", this::disconnect);
        settingsBtn =
                button(
                        "settings",
                        () -> {
                            settings.setVisible(!settings.isVisible());
                            getView().revalidate();
                        });
        useSshCheck.addActionListener(e -> updateActions());
        FormGrid options = new FormGrid();
        options.row(t("executable"), executableField);
        options.row(t("context"), contextField);
        options.row(t("timeout"), timeoutSpinner);
        options.fullRow(Fields.note(t("connectionHint")));
        settings = Layouts.box(0, 0);
        settings.add(options);
        settings.setVisible(false);
        JPanel connection = Layouts.box(0, Tokens.SPACE_SM);
        connection.add(
                Layouts.wrapRow(
                        useSshCheck,
                        sshCombo,
                        sshModeCombo,
                        settingsBtn,
                        connectBtn,
                        disconnectBtn,
                        cancelBtn),
                BorderLayout.NORTH);
        JPanel connectionBody = Layouts.box(0, Tokens.SPACE_SM);
        connectionBody.add(tunnelSettings, BorderLayout.NORTH);
        connectionBody.add(settings, BorderLayout.CENTER);
        connection.add(connectionBody, BorderLayout.CENTER);
        Card card = Card.titled(t("title"));
        card.setContent(connection);
        root.add(card, BorderLayout.NORTH);
        kindCombo = Fields.combo(Kind.values());
        allCheck = Fields.check(t("all"), true);
        filterField = Fields.text("");
        filterField.setToolTipText(t("filter"));
        refreshBtn = button("refresh", this::refresh);
        inspectBtn = button("inspect", () -> selected((c, k, id) -> c.inspect(k, id)));
        logsBtn =
                button(
                        "logs",
                        () -> {
                            int tail = (int) tailSpinner.getValue();
                            selected((c, k, id) -> c.logs(id, tail));
                        });
        statsBtn = button("stats", () -> selected((c, k, id) -> c.stats(id)));
        pullBtn = button("pull", this::pull);
        createBtn = button("create", this::create);
        deleteBtn = button("delete", this::delete);
        tailSpinner = Fields.spinner(200, 1, 10000, 100);
        JPanel browser = Layouts.box(0, Tokens.SPACE_SM);
        FormGrid search = new FormGrid();
        search.row(
                t("resources"),
                Layouts.wrapRow(kindCombo, allCheck, refreshBtn, pullBtn, createBtn));
        search.row(t("filter"), filterField);
        browser.add(search, BorderLayout.NORTH);
        model =
                new DefaultTableModel() {
                    @Override
                    public boolean isCellEditable(int r, int c) {
                        return false;
                    }
                };
        table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getSelectionModel().addListSelectionListener(e -> updateActions());
        browser.add(Fields.scroll(table), BorderLayout.CENTER);
        JPanel operations = Layouts.box(0, Tokens.SPACE_XS);
        JPanel lifecycle = Layouts.wrapRow();
        for (String action : List.of("start", "stop", "restart", "pause", "unpause")) {
            JButton btn = button(action, () -> containerAction(action));
            actions.put(action, btn);
            lifecycle.add(btn);
        }
        lifecycle.add(deleteBtn);
        operations.add(lifecycle, BorderLayout.NORTH);
        operations.add(
                Layouts.wrapRow(
                        inspectBtn, logsBtn, Fields.caption(t("tail")), tailSpinner, statsBtn),
                BorderLayout.SOUTH);
        browser.add(operations, BorderLayout.SOUTH);
        output = Fields.area(8, 60);
        output.setEditable(false);
        Card details = Card.titled(t("output"));
        details.setContent(Fields.scroll(output));
        root.add(Layouts.splitVertical(browser, details, .72, .72), BorderLayout.CENTER);
        statusLabel = Fields.caption(t("disconnected"));
        root.add(statusLabel, BorderLayout.SOUTH);
        kindCombo.addActionListener(
                e -> {
                    resetRows();
                    refresh();
                });
        allCheck.addActionListener(e -> refresh());
        filterField
                .getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            public void insertUpdate(DocumentEvent e) {
                                filter();
                            }

                            public void removeUpdate(DocumentEvent e) {
                                filter();
                            }

                            public void changedUpdate(DocumentEvent e) {
                                filter();
                            }
                        });
        SshConfigStore.getInstance().addChangeListener(sshChanges);
        refreshSshConfigs();
        resetRows();
        updateActions();
        return root;
    }

    private static String t(String key) {
        return I18n.get("docker." + key);
    }

    private JButton button(String key, Runnable action) {
        JButton b = Buttons.secondary(t(key));
        b.addActionListener(e -> action.run());
        return b;
    }

    private Kind kind() {
        return (Kind) kindCombo.getSelectedItem();
    }

    private void resetRows() {
        rows = List.of();
        model.setRowCount(0);
        model.setColumnIdentifiers(kind().columns.stream().map(k -> t("column." + k)).toArray());
        filter();
    }

    private void filter() {
        if (table.getRowSorter() instanceof TableRowSorter<?> sorter) {
            String query = filterField.getText().trim();
            sorter.setRowFilter(
                    query.isEmpty() ? null : RowFilter.regexFilter("(?iu)" + Pattern.quote(query)));
        }
    }

    private void connect() {
        if (closed || busy || client != null) return;
        SshConnectionConfig ssh = (SshConnectionConfig) sshCombo.getSelectedItem();
        if (useSshCheck.isSelected() && ssh == null) {
            UIUtils.error(getView(), t("error.ssh"));
            return;
        }
        try {
            boolean tunnel =
                    useSshCheck.isSelected()
                            && sshModeCombo.getSelectedItem() == SshMode.TCP_TUNNEL;
            DockerConnectionOptions options =
                    new DockerConnectionOptions(
                            executableField.getText(),
                            tunnel ? "" : contextField.getText(),
                            useSshCheck.isSelected() ? ssh.getId() : "",
                            (SshMode) sshModeCombo.getSelectedItem(),
                            tunnelHostField.getText(),
                            (int) tunnelPortSpinner.getValue());
            DockerService service = factory.create(options, (int) timeoutSpinner.getValue());
            client = service;
            submit(
                    service::info,
                    info -> {
                        showOutput(info);
                        statusLabel.setText(
                                t("connected")
                                        + (useSshCheck.isSelected() ? " · " + ssh.getName() : ""));
                        refresh();
                    },
                    true);
        } catch (Exception error) {
            disconnect();
            UIUtils.error(getView(), Errors.describeRoot(error));
        }
    }

    private <T> void submit(Callable<T> work, Consumer<T> success, boolean connecting) {
        if (closed || busy || client == null) return;
        long request = epoch;
        busy = true;
        updateActions();
        statusLabel.setText(t("working"));
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return work.call();
            }

            @Override
            protected void done() {
                if (closed || request != epoch) return;
                busy = false;
                updateActions();
                try {
                    T result = get();
                    statusLabel.setText(t("success"));
                    success.accept(result);
                } catch (Exception error) {
                    if (connecting) disconnect();
                    statusLabel.setText(t("failed"));
                    showOutput(Errors.describeRoot(error));
                    UIUtils.error(getView(), Errors.describeRoot(error));
                }
            }
        }.execute();
    }

    private void refresh() {
        if (client == null || busy || closed) return;
        DockerService service = client;
        Kind selected = kind();
        boolean all = allCheck.isSelected();
        submit(
                () -> service.list(selected, all),
                data -> {
                    rows = data;
                    model.setRowCount(0);
                    for (JsonNode row : data)
                        model.addRow(
                                selected.columns.stream().map(k -> row.path(k).asText()).toArray());
                    statusLabel.setText(I18n.get("docker.count", data.size()));
                    filter();
                    updateActions();
                },
                false);
    }

    @FunctionalInterface
    private interface Operation {
        String execute(DockerService service, Kind kind, String id) throws Exception;
    }

    private String selectedId() {
        int index = table.getSelectedRow();
        if (index < 0) return null;
        return rows.get(table.convertRowIndexToModel(index))
                .path(kind() == Kind.VOLUME ? "Name" : "ID")
                .asText();
    }

    private void selected(Operation operation) {
        String id = selectedId();
        if (id == null || busy || client == null) return;
        DockerService service = client;
        Kind selected = kind();
        submit(() -> operation.execute(service, selected, id), this::showOutput, false);
    }

    private void containerAction(String action) {
        String id = selectedId();
        if (id == null || busy || client == null) return;
        if (!action.equals("start")
                && !UIUtils.confirm(
                        getView(), I18n.get("docker.confirmAction", t(action), id), t("title")))
            return;
        DockerService service = client;
        submit(
                () -> service.action(action, id),
                text -> {
                    showOutput(text);
                    refresh();
                },
                false);
    }

    private void delete() {
        String id = selectedId();
        if (id == null || busy || client == null) return;
        Kind selected = kind();
        if (!UIUtils.confirm(getView(), I18n.get("docker.confirmDelete", selected, id), t("title")))
            return;
        DockerService service = client;
        submit(
                () -> service.delete(selected, id),
                text -> {
                    showOutput(text);
                    refresh();
                },
                false);
    }

    private void pull() {
        if (busy || client == null) return;
        String image = UIUtils.input(getView(), t("image"), "nginx:latest");
        if (image == null || image.isBlank()) return;
        DockerService service = client;
        submit(
                () -> service.pull(image.trim()),
                text -> {
                    showOutput(text);
                    refresh();
                },
                false);
    }

    private void create() {
        if (busy || client == null) return;
        if (kind() == Kind.NETWORK || kind() == Kind.VOLUME) {
            createResource();
            return;
        }
        JTextField image = Fields.text(""), name = Fields.text(""), ports = Fields.text("");
        JTextArea environment = Fields.area(3, 30), mounts = Fields.area(3, 30);
        JComboBox<String> restart =
                Fields.combo(new String[] {"no", "unless-stopped", "always", "on-failure"});
        FormGrid form = new FormGrid();
        form.row(t("image"), image);
        form.row(t("name"), name);
        form.row(t("ports"), ports);
        form.row(t("environment"), Fields.scroll(environment));
        form.row(t("mounts"), Fields.scroll(mounts));
        form.row(t("restartPolicy"), restart);
        form.fullRow(Fields.note(t("createHint")));
        if (JOptionPane.showConfirmDialog(
                        getView(),
                        form,
                        t("create"),
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) return;
        RunOptions options =
                new RunOptions(
                        image.getText().trim(),
                        name.getText().trim(),
                        lines(ports.getText().replace(',', '\n')),
                        lines(environment.getText()),
                        lines(mounts.getText()),
                        (String) restart.getSelectedItem());
        DockerService service = client;
        submit(
                () -> service.create(options),
                text -> {
                    showOutput(text);
                    refresh();
                },
                false);
    }

    private void createResource() {
        Kind selected = kind();
        JTextField name = Fields.text(""),
                driver = Fields.text(selected == Kind.NETWORK ? "bridge" : "local");
        FormGrid form = new FormGrid();
        form.row(t("resourceName"), name);
        form.row(t("column.Driver"), driver);
        if (JOptionPane.showConfirmDialog(
                        getView(),
                        form,
                        t("createResource"),
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) return;
        DockerService service = client;
        String resourceName = name.getText().trim(), resourceDriver = driver.getText().trim();
        submit(
                () -> service.createResource(selected, resourceName, resourceDriver),
                text -> {
                    showOutput(text);
                    refresh();
                },
                false);
    }

    private static List<String> lines(String text) {
        return text.lines().map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private void showOutput(String text) {
        try {
            JsonNode json = Json.mapper().readTree(text);
            if (json != null)
                text = Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (Exception ignored) {
        }
        output.setText(text);
        output.setCaretPosition(0);
    }

    private void refreshSshConfigs() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refreshSshConfigs);
            return;
        }
        if (closed || sshCombo == null) return;
        SshConnectionConfig previous = (SshConnectionConfig) sshCombo.getSelectedItem();
        sshCombo.removeAllItems();
        for (var config : SshConfigStore.getInstance().getAll()) sshCombo.addItem(config);
        if (previous != null) {
            sshCombo.setSelectedItem(null);
            for (int i = 0; i < sshCombo.getItemCount(); i++)
                if (previous.getId().equals(sshCombo.getItemAt(i).getId()))
                    sshCombo.setSelectedIndex(i);
        }
        if (connectBtn != null && kindCombo != null) updateActions();
    }

    private void updateActions() {
        boolean idle = !closed && !busy, connected = client != null;
        boolean tunnel =
                useSshCheck.isSelected() && sshModeCombo.getSelectedItem() == SshMode.TCP_TUNNEL;
        connectBtn.setEnabled(idle && !connected);
        disconnectBtn.setEnabled(!closed && connected);
        cancelBtn.setEnabled(!closed && busy);
        for (JComponent field :
                new JComponent[] {
                    executableField, contextField, timeoutSpinner, useSshCheck, settingsBtn
                }) field.setEnabled(idle && !connected);
        sshCombo.setEnabled(idle && !connected && useSshCheck.isSelected());
        sshModeCombo.setEnabled(idle && !connected && useSshCheck.isSelected());
        tunnelSettings.setVisible(tunnel);
        tunnelHostField.setEnabled(idle && !connected && tunnel);
        tunnelPortSpinner.setEnabled(idle && !connected && tunnel);
        contextField.setEnabled(idle && !connected && !tunnel);
        contextField.setToolTipText(t(tunnel ? "ssh.contextDisabled" : "context"));
        tunnelSettings.revalidate();
        kindCombo.setEnabled(idle);
        allCheck.setEnabled(idle && kind() == Kind.CONTAINER);
        refreshBtn.setEnabled(idle && connected);
        pullBtn.setEnabled(idle && connected);
        createBtn.setEnabled(idle && connected);
        createBtn.setEnabled(idle && connected && kind() != Kind.IMAGE);
        createBtn.setText(t(kind() == Kind.CONTAINER ? "create" : "createResource"));
        boolean selected = idle && connected && table.getSelectedRow() >= 0;
        inspectBtn.setEnabled(selected);
        deleteBtn.setEnabled(selected);
        logsBtn.setEnabled(selected && kind() == Kind.CONTAINER);
        statsBtn.setEnabled(selected && kind() == Kind.CONTAINER);
        for (var entry : actions.entrySet())
            entry.getValue().setEnabled(selected && kind() == Kind.CONTAINER);
    }

    private void disconnect() {
        epoch++;
        busy = false;
        DockerService service = client;
        client = null;
        if (service != null) {
            service.cancel();
            com.aqishi.toolbox.infra.concurrency.DaemonThreads.factory("docker-close")
                    .newThread(service::close)
                    .start();
        }
        if (table != null) {
            resetRows();
            updateActions();
            statusLabel.setText(t("disconnected"));
        }
    }

    @Override
    public void closeResources() {
        closed = true;
        SshConfigStore.getInstance().removeChangeListener(sshChanges);
        if (SwingUtilities.isEventDispatchThread()) disconnect();
        else SwingUtilities.invokeLater(this::disconnect);
    }
}
