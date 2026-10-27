package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.network.application.*;
import com.aqishi.toolbox.feature.network.domain.*;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;
import com.aqishi.toolbox.infra.network.scan.*;
import com.aqishi.toolbox.infra.network.scan.ScanResultRepository.Filter;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;
import com.aqishi.toolbox.vault.ApplicationPaths;

import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.prefs.Preferences;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** The table holds one page; the session owns all results and its TCP connections. */
public class PortScannerPanel extends ToolPanel implements ManagedResourceOwner {
    private final PortScanPreferences preferences;
    private final LinkedHashMap<String, String> userGroups;
    private final LinkedHashMap<String, ScanProfile> profiles;
    private final Map<String, JCheckBox> presetChecks = new LinkedHashMap<>(),
            userChecks = new LinkedHashMap<>();
    private final ExecutorService io = DaemonThreads.single("portscan-ui-io");
    private final Path cacheDirectory;
    private JTextField hostField, customRangeField, excludeTargets, excludePorts, searchField;
    private JCheckBox includeEdges;
    private JSpinner timeoutSpinner,
            threadSpinner,
            perHostSpinner,
            rateSpinner,
            maxHostsSpinner,
            maxProbesSpinner,
            cacheSpinner;
    private JButton startBtn,
            stopBtn,
            configBtn,
            batchBtn,
            previewBtn,
            clearBtn,
            previousBtn,
            nextBtn,
            exportBtn,
            copyBtn;
    private JLabel statusLabel, planLabel, pageLabel, settingsStatusLabel;
    private JProgressBar progressBar;
    private JTable resultTable, hostTable;
    private DefaultTableModel resultTableModel, hostModel;
    private JComboBox<Object> stateCombo;
    private JComboBox<String> exportFormat, exportScope;
    private JComboBox<ProfileChoice> profileCombo;

    private record ProfileChoice(String name) {
        @Override
        public String toString() {
            return name == null ? t("profileNone") : name;
        }
    }

    private JButton importTargetsBtn, saveProfileBtn, deleteProfileBtn, diagnoseBtn;
    private final List<ScanResultRepository.Host> hostRows = new ArrayList<>();
    private JPanel settings, customGroupsPanel;
    private JDialog settingsDialog;
    private javax.swing.Timer timer;
    private volatile boolean closed, preparing, cancelPreparation;
    private volatile long generation;
    private ScanSession session;
    private long offset, lastStored = -1, lastRefresh;
    private boolean pageLoading,
            refreshPending,
            renderingHosts,
            exporting,
            copying,
            importing,
            loadingProfile;
    private final List<ProbeOutcome> visibleResults = new ArrayList<>();
    private final Set<JDialog> diagnosticDialogs = new HashSet<>();
    private String selectedHost = "";
    private Filter activeFilter = new Filter("", ProbeOutcome.State.OPEN, "");
    private ScanSession.Phase shownPhase;
    private Map<String, Object> scanMetadata = Map.of();

    public PortScannerPanel() {
        this(
                Preferences.userNodeForPackage(PortScannerPanel.class),
                ApplicationPaths.systemDefault().getDataDirectory().resolve("cache/port-scans"));
    }

    public PortScannerPanel(Preferences preferences, Path cacheDirectory) {
        super(ToolCatalog.PORT_SCANNER);
        this.preferences = new PortScanPreferences(preferences);
        this.cacheDirectory = cacheDirectory;
        userGroups = this.preferences.groups();
        profiles = this.preferences.profiles();
    }

    private static String t(String key, Object... args) {
        return I18n.get("portscan." + key, args);
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();
        root.add(configCard(), BorderLayout.NORTH);
        root.add(Layouts.splitHorizontal(hostCard(), resultCard(), .24, .24), BorderLayout.CENTER);
        statusLabel = Fields.caption(t("ready"));
        progressBar = new JProgressBar(0, 1000);
        progressBar.setStringPainted(true);
        JPanel footer = Layouts.box(0, Tokens.SPACE_SM);
        footer.add(statusLabel, BorderLayout.NORTH);
        footer.add(progressBar, BorderLayout.SOUTH);
        root.add(footer, BorderLayout.SOUTH);
        buildSettings();
        refreshProfiles(null);
        updateActions();
        timer = new javax.swing.Timer(150, e -> poll());
        timer.start();
        return root;
    }

    private Card configCard() {
        hostField = Fields.mono("127.0.0.1");
        hostField.putClientProperty(
                "JTextField.placeholderText", "192.168.1.10-50,10.0.0.0/24,::1");
        hostField.setToolTipText(t("targetHint"));
        customRangeField = Fields.mono(preferences.get("customPorts", ""));
        customRangeField.putClientProperty(
                "JTextField.placeholderText", "22,80,443,2379,8080-8085,9092");
        customRangeField.setToolTipText(t("portHint"));
        startBtn = Buttons.primary(t("start"));
        startBtn.addActionListener(e -> startScan());
        stopBtn = Buttons.danger(t("stop"));
        stopBtn.addActionListener(e -> stopScan());
        previewBtn = Buttons.secondary(t("preview"));
        previewBtn.addActionListener(e -> preview());
        configBtn = Buttons.secondary(t("settings"));
        configBtn.addActionListener(e -> showSettings());
        batchBtn = Buttons.secondary(t("batch"));
        batchBtn.addActionListener(e -> editTargets());
        importTargetsBtn = Buttons.secondary(t("importTargets"));
        importTargetsBtn.addActionListener(e -> importTargets());
        profileCombo = Fields.combo(new ProfileChoice[0], 220);
        profileCombo.addActionListener(
                e -> {
                    if (!loadingProfile && profileCombo.getSelectedIndex() > 0) {
                        try {
                            loadProfile(((ProfileChoice) profileCombo.getSelectedItem()).name());
                        } catch (Exception error) {
                            showError(error);
                        }
                    }
                });
        saveProfileBtn = Buttons.secondary(t("saveProfile"));
        saveProfileBtn.addActionListener(
                e -> {
                    String name =
                            JOptionPane.showInputDialog(
                                    getView(),
                                    t("profileName"),
                                    profileCombo.getSelectedIndex() > 0
                                            ? profileCombo.getSelectedItem()
                                            : "");
                    if (name != null)
                        try {
                            saveProfile(name.trim());
                        } catch (Exception error) {
                            showError(error);
                        }
                });
        deleteProfileBtn = Buttons.danger(t("deleteProfile"));
        deleteProfileBtn.addActionListener(
                e -> {
                    if (profileCombo.getSelectedIndex() > 0)
                        try {
                            deleteProfile(((ProfileChoice) profileCombo.getSelectedItem()).name());
                        } catch (Exception error) {
                            showError(error);
                        }
                });
        FormGrid form = new FormGrid();
        form.row(t("targets"), hostField, Layouts.wrapRow(batchBtn, importTargetsBtn));
        form.row(t("customPorts"), customRangeField, configBtn);
        form.row(t("profiles"), Layouts.wrapRow(profileCombo, saveProfileBtn, deleteProfileBtn));
        planLabel = Fields.caption(t("targetHint"));
        form.fullRow(planLabel);
        Card card = Card.titled(t("config"));
        card.setContent(form);
        card.addHeaderAction(previewBtn);
        card.addHeaderAction(stopBtn);
        card.addHeaderAction(startBtn);
        return card;
    }

    private Card hostCard() {
        hostModel = readOnly(t("host"), t("probed"), t("open"), t("errors"));
        hostTable = new JTable(hostModel);
        hostTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        for (int i = 0; i < 4; i++)
            hostTable.getColumnModel().getColumn(i).setPreferredWidth(i == 0 ? 190 : 70);
        hostTable.setDefaultRenderer(
                Object.class,
                new javax.swing.table.DefaultTableCellRenderer() {
                    @Override
                    public Component getTableCellRendererComponent(
                            JTable table,
                            Object value,
                            boolean selected,
                            boolean focus,
                            int row,
                            int column) {
                        Component result =
                                super.getTableCellRendererComponent(
                                        table, value, selected, focus, row, column);
                        setToolTipText(String.valueOf(value));
                        return result;
                    }
                });
        hostTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        hostTable.setRowHeight(Tokens.TABLE_ROW_HEIGHT);
        hostTable
                .getSelectionModel()
                .addListSelectionListener(
                        e -> {
                            if (!e.getValueIsAdjusting() && !renderingHosts) {
                                int row = hostTable.getSelectedRow();
                                selectedHost = row < 0 ? "" : hostRows.get(row).ip();
                                offset = 0;
                                updateFilter();
                            }
                        });
        JButton all = Buttons.secondary(t("allHosts"));
        all.addActionListener(
                e -> {
                    hostTable.clearSelection();
                    selectedHost = "";
                    offset = 0;
                    updateFilter();
                });
        Card card = Card.flush(t("hosts"));
        card.setContent(Fields.scroll(hostTable));
        card.setFooter(Layouts.wrapRow(all));
        return card;
    }

    private Card resultCard() {
        stateCombo =
                Fields.combo(
                        new Object[] {
                            t("allStates"),
                            ProbeOutcome.State.OPEN,
                            ProbeOutcome.State.CONNECT_FAILED,
                            ProbeOutcome.State.TIMEOUT,
                            ProbeOutcome.State.UNREACHABLE,
                            ProbeOutcome.State.DNS_FAILED,
                            ProbeOutcome.State.ERROR,
                            ProbeOutcome.State.CANCELLED
                        },
                        150);
        stateCombo.setRenderer(
                new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(
                            JList<?> list,
                            Object value,
                            int index,
                            boolean selected,
                            boolean focus) {
                        return super.getListCellRendererComponent(
                                list,
                                value instanceof ProbeOutcome.State state
                                        ? t("state." + state)
                                        : value,
                                index,
                                selected,
                                focus);
                    }
                });
        stateCombo.setSelectedItem(ProbeOutcome.State.OPEN);
        stateCombo.addActionListener(
                e -> {
                    offset = 0;
                    updateFilter();
                });
        searchField = Fields.text("", t("filterHint"));
        searchField.addActionListener(
                e -> {
                    offset = 0;
                    updateFilter();
                });
        JButton apply = Buttons.secondary(t("apply"));
        apply.addActionListener(
                e -> {
                    offset = 0;
                    updateFilter();
                });
        clearBtn = Buttons.danger(t("clear"));
        clearBtn.addActionListener(e -> clear());
        JPanel toolbar = Layouts.box(Tokens.SPACE_SM, 0);
        toolbar.add(searchField, BorderLayout.CENTER);
        toolbar.add(Layouts.wrapRow(stateCombo, apply, clearBtn), BorderLayout.EAST);
        resultTableModel =
                readOnly(
                        t("source"),
                        t("ip"),
                        t("port"),
                        t("protocol"),
                        t("state"),
                        t("elapsed"),
                        t("service"),
                        t("detail"));
        resultTable = new JTable(resultTableModel);
        resultTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultTable.getSelectionModel().addListSelectionListener(e -> updateActions());
        resultTable.setRowHeight(Tokens.TABLE_ROW_HEIGHT);
        resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths = {160, 160, 65, 65, 120, 100, 170, 300};
        for (int i = 0; i < widths.length; i++)
            resultTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        previousBtn = Buttons.secondary(t("previous"));
        previousBtn.addActionListener(
                e -> {
                    offset = Math.max(0, offset - 500);
                    refreshPage();
                });
        nextBtn = Buttons.secondary(t("next"));
        nextBtn.addActionListener(
                e -> {
                    offset += 500;
                    refreshPage();
                });
        pageLabel = Fields.caption(t("page", 0, 0));
        copyBtn = Buttons.secondary(t("copy"));
        copyBtn.addActionListener(e -> copyOpen());
        diagnoseBtn = Buttons.secondary(t("diagnose"));
        diagnoseBtn.addActionListener(e -> diagnoseSelected());
        exportFormat = Fields.combo(new String[] {"CSV", "JSON"}, 90);
        exportScope = Fields.combo(new String[] {t("exportFiltered"), t("exportAll")}, 140);
        exportBtn = Buttons.secondary(t("export"));
        exportBtn.addActionListener(e -> export());
        JPanel footer =
                Layouts.stack(
                        Tokens.SPACE_SM,
                        Layouts.wrapRow(previousBtn, pageLabel, nextBtn),
                        Layouts.wrapRow(
                                copyBtn, diagnoseBtn, exportScope, exportFormat, exportBtn));
        JPanel content = Layouts.box(0, Tokens.SPACE_SM);
        content.add(toolbar, BorderLayout.NORTH);
        content.add(Fields.scroll(resultTable), BorderLayout.CENTER);
        Card card = Card.flush(t("results"));
        card.setContent(content);
        card.setFooter(footer);
        return card;
    }

    private static final class PageTableModel extends DefaultTableModel {
        PageTableModel(String... columns) {
            super(columns, 0);
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            return false;
        }

        void install(List<Object[]> rows) {
            java.util.Vector<java.util.Vector> replacement = new java.util.Vector<>(rows.size());
            for (Object[] row : rows) replacement.add(new java.util.Vector<>(Arrays.asList(row)));
            dataVector = replacement;
            fireTableDataChanged();
        }
    }

    private static DefaultTableModel readOnly(String... columns) {
        return new PageTableModel(columns);
    }

    private void buildSettings() {
        settings = new JPanel(new BorderLayout());
        settings.setOpaque(false);
        JTabbedPane tabs = new JTabbedPane();
        excludeTargets = Fields.mono("");
        excludePorts = Fields.mono(preferences.get("excludePorts", ""));
        includeEdges =
                Fields.check(
                        t("includeEdges"), preferences.get("includeEdges", "false").equals("true"));
        FormGrid targetForm = new FormGrid();
        targetForm.row(t("excludeTargets"), excludeTargets);
        targetForm.fullRow(includeEdges);
        targetForm.fullRow(Fields.note(t("targetHint")));
        JPanel targetBody = Layouts.page();
        targetBody.add(targetForm, BorderLayout.NORTH);
        tabs.addTab(t("targets"), targetBody);
        JPanel groupBody = Layouts.page();
        JPanel builtins = Layouts.stack(Tokens.SPACE_SM);
        for (String group : PortPresets.groups()) {
            String ports = PortPresets.ports(group);
            JCheckBox check =
                    Fields.check(
                            t("group." + group)
                                    + " ("
                                    + PortExpressionParser.parse(ports).size()
                                    + ")",
                            preferences
                                    .get(
                                            "group." + group,
                                            List.of("web", "database", "messaging", "remote")
                                                            .contains(group)
                                                    ? "true"
                                                    : "false")
                                    .equals("true"));
            check.setToolTipText(ports);
            presetChecks.put(group, check);
            builtins.add(Layouts.wrapRow(check, Fields.caption(ports)));
        }
        JButton common = Buttons.secondary(t("common"));
        common.addActionListener(
                e -> {
                    presetChecks.forEach(
                            (name, check) ->
                                    check.setSelected(
                                            List.of("web", "database", "messaging", "remote")
                                                    .contains(name)));
                });
        JButton none = Buttons.secondary(t("none"));
        none.addActionListener(
                e -> {
                    presetChecks.values().forEach(check -> check.setSelected(false));
                    userChecks.values().forEach(check -> check.setSelected(false));
                });
        JButton all = Buttons.secondary(t("allPorts"));
        all.addActionListener(
                e -> {
                    presetChecks.values().forEach(check -> check.setSelected(false));
                    userChecks.values().forEach(check -> check.setSelected(false));
                    customRangeField.setText("1-65535");
                });
        JButton add = Buttons.secondary(t("saveGroup"));
        add.addActionListener(e -> editGroup(null));
        customGroupsPanel = Layouts.box();
        rebuildGroups();
        FormGrid portForm = new FormGrid();
        portForm.fullRow(builtins);
        portForm.fullRow(Layouts.wrapRow(common, none, all, add));
        portForm.row(t("excludePorts"), excludePorts);
        portForm.fullRow(customGroupsPanel);
        portForm.fullRow(Fields.note(t("portHint")));
        groupBody.add(portForm, BorderLayout.NORTH);
        tabs.addTab(t("portGroups"), Fields.scroll(groupBody));
        timeoutSpinner = spinner("timeout", 500, 50, 60000, 50);
        threadSpinner = spinner("threads", 64, 1, 256, 1);
        perHostSpinner = spinner("perHost", 8, 1, 64, 1);
        rateSpinner = spinner("rate", 200, 1, 10000, 10);
        maxHostsSpinner = spinner("maxHosts", 4096, 1, 65536, 256);
        maxProbesSpinner = spinner("maxProbes", 1000000, 1, 100000000, 10000);
        cacheSpinner = spinner("cache", 256, 16, 1024, 16);
        FormGrid advanced = new FormGrid();
        advanced.rowCompact(t("timeout"), timeoutSpinner);
        advanced.rowCompact(t("threads"), threadSpinner);
        advanced.rowCompact(t("perHost"), perHostSpinner);
        advanced.rowCompact(t("rate"), rateSpinner);
        advanced.rowCompact(t("maxHosts"), maxHostsSpinner);
        advanced.rowCompact(t("maxProbes"), maxProbesSpinner);
        advanced.rowCompact(t("cache"), cacheSpinner);
        advanced.fullRow(Fields.note(t("advancedHint")));
        JPanel advancedBody = Layouts.page();
        advancedBody.add(advanced, BorderLayout.NORTH);
        tabs.addTab(t("advanced"), advancedBody);
        settings.add(tabs, BorderLayout.CENTER);
    }

    private JSpinner spinner(String name, int value, int min, int max, int step) {
        JSpinner spinner = Fields.spinner(preferences.get(name, value, min, max), min, max, step);
        spinner.setPreferredSize(new Dimension(150, Tokens.CONTROL_HEIGHT));
        return spinner;
    }

    private void showSettings() {
        if (active() || closed) return;
        JDialog dialog =
                new JDialog(
                        SwingUtilities.getWindowAncestor(getView()),
                        t("settings"),
                        Dialog.ModalityType.APPLICATION_MODAL);
        settingsDialog = dialog;
        JPanel body = Layouts.page();
        body.add(settings, BorderLayout.CENTER);
        JButton done = Buttons.primary(t("done"));
        done.addActionListener(
                e -> {
                    try {
                        saveSettings();
                        dialog.dispose();
                    } catch (Exception error) {
                        showError(error);
                    }
                });
        settingsStatusLabel = Fields.caption("");
        body.add(
                Layouts.stack(Tokens.SPACE_SM, settingsStatusLabel, Layouts.wrapRow(done)),
                BorderLayout.SOUTH);
        dialog.setContentPane(body);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.getRootPane()
                .registerKeyboardAction(
                        e -> dialog.dispose(),
                        KeyStroke.getKeyStroke("ESCAPE"),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);
        Rectangle screen =
                GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        dialog.setSize(Math.min(900, screen.width), Math.min(720, screen.height));
        dialog.setLocationRelativeTo(getView());
        try {
            dialog.setVisible(true);
        } finally {
            dialog.dispose();
            settingsDialog = null;
            preview();
        }
    }

    private void editTargets() {
        JTextArea area = Fields.area(12, 55);
        area.setText(hostField.getText().replace(",", "\n"));
        if (JOptionPane.showConfirmDialog(
                        getView(),
                        Fields.scroll(area),
                        t("batch"),
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE)
                == JOptionPane.OK_OPTION) {
            try {
                applyTargetText(area.getText());
            } catch (Exception error) {
                showError(error);
            }
        }
    }

    private void rebuildGroups() {
        Map<String, Boolean> selections = new HashMap<>();
        userChecks.forEach((name, check) -> selections.put(name, check.isSelected()));
        userChecks.clear();
        JPanel rows = Layouts.stack(Tokens.SPACE_SM);
        userGroups.forEach(
                (name, ports) -> {
                    JCheckBox check =
                            Fields.check(
                                    name + " (" + PortExpressionParser.parse(ports).size() + ")",
                                    selections.getOrDefault(
                                            name,
                                            preferences
                                                    .get(userSelectionKey(name), "false")
                                                    .equals("true")));
                    check.setToolTipText(ports);
                    userChecks.put(name, check);
                    JButton edit = Buttons.snug(t("edit"));
                    edit.addActionListener(e -> editGroup(name));
                    JButton delete = Buttons.snug(t("delete"));
                    delete.addActionListener(
                            e -> {
                                try {
                                    LinkedHashMap<String, String> replacement =
                                            new LinkedHashMap<>(userGroups);
                                    replacement.remove(name);
                                    preferences.saveGroups(replacement);
                                    userGroups.clear();
                                    userGroups.putAll(replacement);
                                    rebuildGroups();
                                } catch (Exception error) {
                                    showError(error);
                                }
                            });
                    rows.add(Layouts.wrapRow(check, edit, delete));
                });
        customGroupsPanel.removeAll();
        customGroupsPanel.add(Fields.scroll(rows));
        customGroupsPanel.setPreferredSize(new Dimension(300, 140));
        customGroupsPanel.revalidate();
        customGroupsPanel.repaint();
    }

    private void editGroup(String previous) {
        String composed = customRangeField.getText();
        if (previous == null) {
            try {
                composed = PortExpressionParser.format(selectedPorts());
            } catch (IllegalArgumentException ignored) {
            }
        }
        JTextField name = Fields.text(previous == null ? "" : previous),
                ports = Fields.mono(previous == null ? composed : userGroups.get(previous));
        FormGrid form = new FormGrid();
        form.row(t("groupName"), name);
        form.row(t("customPorts"), ports);
        if (JOptionPane.showConfirmDialog(
                        settingsDialog,
                        form,
                        t("saveGroup"),
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) return;
        try {
            String key = name.getText().trim();
            if (key.isEmpty() || key.length() > 80 || userGroups.size() >= 100 && previous == null)
                throw new IllegalArgumentException("portscan.error.group");
            if (!key.equals(previous) && userGroups.containsKey(key))
                throw new IllegalArgumentException("portscan.error.group");
            if (PortExpressionParser.parse(ports.getText()).isEmpty())
                throw new IllegalArgumentException("portscan.error.noPorts");
            LinkedHashMap<String, String> replacement = new LinkedHashMap<>(userGroups);
            if (previous != null) replacement.remove(previous);
            replacement.put(key, ports.getText());
            preferences.saveGroups(replacement);
            boolean selected =
                    previous != null
                            && userChecks.get(previous) != null
                            && userChecks.get(previous).isSelected();
            preferences.set(userSelectionKey(key), Boolean.toString(selected));
            userGroups.clear();
            userGroups.putAll(replacement);
            rebuildGroups();
        } catch (Exception error) {
            showError(error);
        }
    }

    private ScanPlan.Settings readSettings() {
        for (JSpinner spinner :
                List.of(
                        timeoutSpinner,
                        threadSpinner,
                        perHostSpinner,
                        rateSpinner,
                        maxHostsSpinner,
                        maxProbesSpinner,
                        cacheSpinner))
            try {
                spinner.commitEdit();
            } catch (java.text.ParseException error) {
                throw new IllegalArgumentException("portscan.error.settings");
            }
        return new ScanPlan.Settings(
                value(timeoutSpinner),
                value(threadSpinner),
                value(perHostSpinner),
                value(rateSpinner),
                value(maxHostsSpinner),
                value(maxProbesSpinner),
                value(cacheSpinner));
    }

    private List<Integer> selectedPorts() {
        List<String> presets = new ArrayList<>();
        presetChecks.forEach(
                (name, check) -> {
                    if (check.isSelected()) presets.add(PortPresets.ports(name));
                });
        userChecks.forEach(
                (name, check) -> {
                    if (check.isSelected()) presets.add(userGroups.get(name));
                });
        return PortExpressionParser.combine(
                presets, customRangeField.getText(), excludePorts.getText());
    }

    private ScanPlan plan() {
        return new ScanPlan(
                ScanTargetParser.parse(hostField.getText(), includeEdges.isSelected()),
                ScanTargetParser.parse(excludeTargets.getText(), true),
                selectedPorts(),
                readSettings());
    }

    private static int value(JSpinner spinner) {
        return ((Number) spinner.getValue()).intValue();
    }

    void saveSettings() {
        if (active() || closed) throw new IllegalStateException("portscan.error.busy");
        ScanPlan.Settings values = readSettings();
        PortExpressionParser.parse(customRangeField.getText());
        PortExpressionParser.parse(excludePorts.getText());
        persist(values);
    }

    void applyTargetText(String text) {
        String normalized = String.join(",", PortExpressionParser.tokens(text));
        var parsed = ScanTargetParser.parse(normalized, includeEdges.isSelected());
        if (parsed.numericCount() == 0 && parsed.names().isEmpty())
            throw new IllegalArgumentException("portscan.error.noTargets");
        var remaining =
                ScanTargetParser.subtract(
                        parsed, ScanTargetParser.parse(excludeTargets.getText(), true));
        ScanPlan.checkBudget(remaining.numericCount(), 1, readSettings());
        hostField.setText(normalized);
        preview();
    }

    private void importTargets() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("TXT", "txt"));
        if (chooser.showOpenDialog(getView()) == JFileChooser.APPROVE_OPTION)
            importTargets(chooser.getSelectedFile().toPath());
    }

    void importTargets(Path path) {
        if (active() || closed || exporting) return;
        importing = true;
        long ticket = generation;
        updateActions();
        io.execute(
                () -> {
                    String text = null;
                    Exception failure = null;
                    try {
                        text = ScanTargetFiles.read(path);
                    } catch (Exception error) {
                        failure = error;
                    }
                    String completed = text;
                    Exception error = failure;
                    SwingUtilities.invokeLater(
                            () -> {
                                importing = false;
                                if (closed || generation != ticket) return;
                                try {
                                    if (error != null) throw error;
                                    applyTargetText(completed);
                                    statusLabel.setText(t("imported"));
                                } catch (Exception invalid) {
                                    showError(invalid);
                                } finally {
                                    updateActions();
                                }
                            });
                });
    }

    private void refreshProfiles(String selected) {
        loadingProfile = true;
        try {
            profileCombo.removeAllItems();
            profileCombo.addItem(new ProfileChoice(null));
            profiles.keySet().forEach(name -> profileCombo.addItem(new ProfileChoice(name)));
            if (selected != null) profileCombo.setSelectedItem(new ProfileChoice(selected));
        } finally {
            loadingProfile = false;
        }
    }

    void saveProfile(String name) {
        if (active() || exporting || closed) throw new IllegalStateException("portscan.error.busy");
        if (name.isBlank()
                || name.length() > 80
                || profiles.size() >= 100 && !profiles.containsKey(name))
            throw new IllegalArgumentException("portscan.error.profile");
        ScanPlan compiled = plan();
        ScanProfile saved =
                new ScanProfile(
                        hostField.getText(),
                        excludeTargets.getText(),
                        includeEdges.isSelected(),
                        PortExpressionParser.format(compiled.ports()),
                        compiled.settings());
        LinkedHashMap<String, ScanProfile> replacement = new LinkedHashMap<>(profiles);
        replacement.put(name, saved);
        preferences.saveProfiles(replacement);
        profiles.clear();
        profiles.putAll(replacement);
        refreshProfiles(name);
        updateActions();
        statusLabel.setText(t("profileSaved"));
    }

    void loadProfile(String name) {
        if (active() || exporting || closed) throw new IllegalStateException("portscan.error.busy");
        ScanProfile saved = profiles.get(name);
        if (saved == null) throw new IllegalArgumentException("portscan.error.profile");
        hostField.setText(saved.targets());
        excludeTargets.setText(saved.excludedTargets());
        includeEdges.setSelected(saved.includeEdges());
        presetChecks.values().forEach(check -> check.setSelected(false));
        userChecks.values().forEach(check -> check.setSelected(false));
        customRangeField.setText(saved.ports());
        excludePorts.setText("");
        ScanPlan.Settings values = saved.settings();
        timeoutSpinner.setValue(values.timeoutMillis());
        threadSpinner.setValue(values.concurrency());
        perHostSpinner.setValue(values.perHost());
        rateSpinner.setValue(values.rate());
        maxHostsSpinner.setValue(values.maxHosts());
        maxProbesSpinner.setValue((int) values.maxProbes());
        cacheSpinner.setValue(values.cacheMiB());
        preview();
        updateActions();
    }

    void deleteProfile(String name) {
        if (active() || exporting || closed) throw new IllegalStateException("portscan.error.busy");
        LinkedHashMap<String, ScanProfile> replacement = new LinkedHashMap<>(profiles);
        replacement.remove(name);
        preferences.saveProfiles(replacement);
        profiles.clear();
        profiles.putAll(replacement);
        refreshProfiles(null);
        updateActions();
    }

    private void diagnoseSelected() {
        int row = resultTable.getSelectedRow();
        if (closed || active() || row < 0 || row >= visibleResults.size()) return;
        ProbeOutcome selected = visibleResults.get(row);
        if (selected.state() != ProbeOutcome.State.OPEN) return;
        NetDiagnosticsPanel diagnostic = new NetDiagnosticsPanel();
        JDialog dialog =
                new JDialog(
                        SwingUtilities.getWindowAncestor(getView()),
                        t("diagnose"),
                        Dialog.ModalityType.APPLICATION_MODAL);
        diagnosticDialogs.add(dialog);
        JPanel content = Layouts.page();
        content.add(diagnostic.getView(), BorderLayout.CENTER);
        diagnostic.useEndpoint(
                selected.ip(),
                selected.port(),
                selected.source(),
                session == null ? 5000 : session.plan().settings().timeoutMillis());
        JButton done = Buttons.primary(t("done"));
        done.addActionListener(e -> dialog.dispose());
        content.add(Layouts.wrapRow(done), BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        Rectangle screen =
                GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        dialog.setSize(Math.min(1100, screen.width), Math.min(780, screen.height));
        dialog.setLocationRelativeTo(getView());
        try {
            dialog.setVisible(true);
        } finally {
            diagnostic.closeResources();
            dialog.dispose();
            diagnosticDialogs.remove(dialog);
        }
    }

    private void preview() {
        try {
            ScanPlan plan = plan();
            long hosts = plan.targets().numericCount(), total = hosts * plan.ports().size();
            double seconds =
                    Math.max(
                            (double) total / plan.settings().rate(),
                            (double) total
                                    * plan.settings().timeoutMillis()
                                    / 1000
                                    / Math.min(
                                            plan.settings().concurrency(),
                                            Math.max(1, hosts) * plan.settings().perHost()));
            planLabel.setText(
                    t(
                            "plan",
                            hosts,
                            plan.ports().size(),
                            total,
                            plan.targets().names().size(),
                            (long) Math.ceil(seconds)));
            String ranges =
                    plan.targets().ranges().stream()
                            .limit(12)
                            .map(
                                    range ->
                                            range.source()
                                                    + " → "
                                                    + ScanTargetParser.format(range.first())
                                                    + "-"
                                                    + ScanTargetParser.format(range.last()))
                            .collect(java.util.stream.Collectors.joining("; "));
            planLabel.setToolTipText(planLabel.getText() + " | " + ranges);
        } catch (Exception error) {
            showError(error);
        }
    }

    protected ScanSession createSession(ScanPlan plan) throws Exception {
        return new ScanSession(
                plan,
                new ScanPlanResolver(),
                new TcpConnectProbe(),
                new ScanResultRepository(cacheDirectory, plan.settings().cacheMiB()));
    }

    private void startScan() {
        if (active() || closed || exporting) return;
        final ScanPlan plan;
        try {
            plan = plan();
            persist(plan.settings());
        } catch (Exception error) {
            showError(error);
            return;
        }
        preparing = true;
        cancelPreparation = false;
        long ticket = ++generation;
        ScanSession previous = session;
        session = null;
        offset = 0;
        lastStored = -1;
        selectedHost = "";
        shownPhase = null;
        hostRows.clear();
        hostModel.setRowCount(0);
        resultTableModel.setRowCount(0);
        visibleResults.clear();
        statusLabel.setText(t("resolving"));
        updateActions();
        preview();
        scanMetadata =
                Map.of(
                        "started",
                        System.currentTimeMillis(),
                        "targets",
                        hostField.getText(),
                        "excludedTargets",
                        excludeTargets.getText(),
                        "ports",
                        plan.ports(),
                        "timeoutMillis",
                        plan.settings().timeoutMillis(),
                        "concurrency",
                        plan.settings().concurrency(),
                        "perHost",
                        plan.settings().perHost(),
                        "rate",
                        plan.settings().rate());
        io.execute(
                () -> {
                    if (previous != null) previous.close();
                    ScanSession created = null;
                    Exception failure = null;
                    try {
                        if (!closed && !cancelPreparation) created = createSession(plan);
                    } catch (Exception error) {
                        failure = error;
                    }
                    ScanSession ready = created;
                    Exception error = failure;
                    SwingUtilities.invokeLater(
                            () -> {
                                if (closed || ticket != generation || cancelPreparation) {
                                    if (ready != null) ioCleanup(ready);
                                    preparing = false;
                                    if (!closed) {
                                        statusLabel.setText(t("cancelled"));
                                        updateActions();
                                    }
                                    return;
                                }
                                preparing = false;
                                if (error != null) {
                                    showError(error);
                                    updateActions();
                                    return;
                                }
                                session = ready;
                                ready.start();
                                updateFilter();
                                updateActions();
                            });
                });
    }

    private void persist(ScanPlan.Settings values) {
        preferences.set("timeout", values.timeoutMillis());
        preferences.set("threads", values.concurrency());
        preferences.set("perHost", values.perHost());
        preferences.set("rate", values.rate());
        preferences.set("maxHosts", values.maxHosts());
        preferences.set("maxProbes", (int) values.maxProbes());
        preferences.set("cache", values.cacheMiB());
        preferences.set("includeEdges", Boolean.toString(includeEdges.isSelected()));
        preferences.set("customPorts", customRangeField.getText());
        preferences.set("excludePorts", excludePorts.getText());
        presetChecks.forEach(
                (name, check) ->
                        preferences.set("group." + name, Boolean.toString(check.isSelected())));
        userChecks.forEach(
                (name, check) ->
                        preferences.set(
                                userSelectionKey(name), Boolean.toString(check.isSelected())));
    }

    private static String userSelectionKey(String name) {
        return "user."
                + UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private boolean active() {
        return importing || preparing || session != null && !session.progress().finished();
    }

    private void stopScan() {
        cancelPreparation = true;
        if (session != null) session.cancel();
        statusLabel.setText(t("stopping"));
        updateActions();
    }

    private void poll() {
        if (closed || session == null) return;
        ScanSession.Progress progress = session.progress();
        progressBar.setIndeterminate(progress.phase() == ScanSession.Phase.RESOLVING);
        progressBar.setValue(
                progress.total() == 0 ? 0 : (int) (1000 * progress.completed() / progress.total()));
        progressBar.setString(
                t(
                        "progress",
                        progress.completedHosts(),
                        progress.hosts(),
                        progress.completed(),
                        progress.total(),
                        progress.open(),
                        progress.timeouts(),
                        progress.dnsFailures()));
        if (shownPhase != progress.phase()) {
            shownPhase = progress.phase();
            if (progress.phase() == ScanSession.Phase.RESOLVING)
                statusLabel.setText(t("resolving"));
            else if (progress.phase() == ScanSession.Phase.FAILED)
                statusLabel.setText(localize(progress.error()));
            else statusLabel.setText(t("phase." + progress.phase()));
        }
        if (progress.stored() != lastStored
                && System.nanoTime() - lastRefresh > TimeUnit.MILLISECONDS.toNanos(500)) {
            lastStored = progress.stored();
            lastRefresh = System.nanoTime();
            refreshPage();
        }
        updateActions();
    }

    private void updateFilter() {
        if (stateCombo == null) return;
        activeFilter =
                new Filter(
                        selectedHost,
                        stateCombo.getSelectedItem() instanceof ProbeOutcome.State state
                                ? state
                                : null,
                        searchField.getText().trim());
        refreshPage();
    }

    private void refreshPage() {
        if (closed || session == null) return;
        if (pageLoading || exporting) {
            refreshPending = true;
            return;
        }
        pageLoading = true;
        refreshPending = false;
        ScanSession current = session;
        long ticket = generation, start = offset;
        Filter filter = activeFilter;
        String preservedHost = selectedHost;
        io.execute(
                () -> {
                    ScanResultRepository.Page page = null;
                    List<ScanResultRepository.Host> hosts = List.of();
                    Exception failure = null;
                    try {
                        page = current.repository().page(filter, start, 500);
                        hosts = current.repository().hosts();
                    } catch (Exception error) {
                        failure = error;
                    }
                    var completed = page;
                    var summaries = hosts;
                    Exception error = failure;
                    SwingUtilities.invokeLater(
                            () -> {
                                pageLoading = false;
                                if (closed || ticket != generation) {
                                    if (!closed && refreshPending) refreshPage();
                                    return;
                                }
                                if (error != null) {
                                    showError(error);
                                    return;
                                }
                                if (!filter.equals(activeFilter) || start != offset) {
                                    refreshPage();
                                    return;
                                }
                                List<Object[]> resultRows = new ArrayList<>();
                                for (ProbeOutcome row : completed.rows())
                                    resultRows.add(
                                            new Object[] {
                                                row.source(),
                                                row.ip(),
                                                row.port(),
                                                "TCP",
                                                t("state." + row.state()),
                                                row.elapsedMicros() / 1000.0,
                                                PortPresets.service(row.port()),
                                                row.detail()
                                            });
                                ((PageTableModel) resultTableModel).install(resultRows);
                                visibleResults.clear();
                                visibleResults.addAll(completed.rows());
                                updateActions();
                                pageLabel.setText(
                                        t(
                                                "page",
                                                completed.total() == 0 ? 0 : start / 500 + 1,
                                                (completed.total() + 499) / 500));
                                previousBtn.setEnabled(start > 0);
                                nextBtn.setEnabled(start + 500 < completed.total());
                                renderingHosts = true;
                                try {
                                    hostRows.clear();
                                    hostRows.addAll(summaries);
                                    List<Object[]> rows = new ArrayList<>();
                                    int selected = -1;
                                    for (var host : summaries) {
                                        rows.add(
                                                new Object[] {
                                                    host.ip().startsWith("dns:")
                                                            ? host.source()
                                                            : host.ip(),
                                                    host.probed(),
                                                    host.open(),
                                                    host.errors()
                                                });
                                        if (host.ip().equals(preservedHost))
                                            selected = rows.size() - 1;
                                    }
                                    ((PageTableModel) hostModel).install(rows);
                                    if (selected >= 0)
                                        hostTable.setRowSelectionInterval(selected, selected);
                                } finally {
                                    renderingHosts = false;
                                }
                                if (refreshPending) refreshPage();
                            });
                });
    }

    private void clear() {
        if (active() || exporting) return;
        generation++;
        ScanSession old = session;
        session = null;
        if (old != null) ioCleanup(old);
        hostRows.clear();
        hostModel.setRowCount(0);
        resultTableModel.setRowCount(0);
        visibleResults.clear();
        offset = 0;
        lastStored = -1;
        progressBar.setValue(0);
        progressBar.setString("");
        pageLabel.setText(t("page", 0, 0));
        statusLabel.setText(t("ready"));
        updateActions();
    }

    private void copyOpen() {
        if (session == null || exporting || copying || closed) return;
        copying = true;
        updateActions();
        ScanSession current = session;
        long ticket = generation;
        Filter filter = activeFilter;
        io.execute(
                () -> {
                    try {
                        String text = current.repository().copyOpen(filter, 1024 * 1024);
                        SwingUtilities.invokeLater(
                                () -> {
                                    try {
                                        if (!closed && ticket == generation) {
                                            UIUtils.copyToClipboard(text);
                                            statusLabel.setText(t("copied"));
                                        }
                                    } catch (Exception error) {
                                        if (!closed && ticket == generation) showError(error);
                                    } finally {
                                        copying = false;
                                        updateActions();
                                    }
                                });
                    } catch (Exception error) {
                        SwingUtilities.invokeLater(
                                () -> {
                                    copying = false;
                                    if (!closed && ticket == generation) showError(error);
                                    updateActions();
                                });
                    }
                });
    }

    private void export() {
        if (session == null || exporting || active()) return;
        boolean json = exportFormat.getSelectedIndex() == 1;
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("port-scan." + (json ? "json" : "csv")));
        if (chooser.showSaveDialog(getView()) != JFileChooser.APPROVE_OPTION) return;
        Path destination = chooser.getSelectedFile().toPath().toAbsolutePath();
        if (Files.exists(destination) && !UIUtils.confirm(getView(), t("overwrite"), t("export")))
            return;
        ScanSession current = session;
        long ticket = generation;
        Filter filter = exportScope.getSelectedIndex() == 1 ? Filter.all() : activeFilter;
        Map<String, Object> metadata = new LinkedHashMap<>(scanMetadata);
        metadata.put("phase", current.progress().phase().name());
        metadata.put("completed", current.progress().completed());
        metadata.put("total", current.progress().total());
        metadata.put(
                "filter",
                Map.of(
                        "host",
                        filter.host(),
                        "state",
                        filter.state() == null ? "ALL" : filter.state().name(),
                        "search",
                        filter.search()));
        exporting = true;
        updateActions();
        io.execute(
                () -> {
                    Path temporary = null;
                    long count = 0;
                    Exception failure = null;
                    try {
                        temporary =
                                Files.createTempFile(
                                        destination.getParent(), ".portscan-export-", ".tmp");
                        count =
                                current.repository()
                                        .export(temporary, json, filter, metadata, () -> closed);
                        if (closed) throw new java.io.InterruptedIOException();
                        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception error) {
                        failure = error;
                    } finally {
                        if (temporary != null)
                            try {
                                Files.deleteIfExists(temporary);
                            } catch (Exception ignored) {
                            }
                    }
                    long rows = count;
                    Exception error = failure;
                    SwingUtilities.invokeLater(
                            () -> {
                                exporting = false;
                                if (closed || ticket != generation) return;
                                if (error == null) statusLabel.setText(t("exported", rows));
                                else showError(error);
                                updateActions();
                                if (refreshPending) refreshPage();
                            });
                });
    }

    private void updateActions() {
        if (startBtn == null || stateCombo == null) return;
        boolean running = active();
        startBtn.setEnabled(!closed && !running && !exporting);
        stopBtn.setEnabled(
                !closed && (preparing || session != null && !session.progress().finished()));
        configBtn.setEnabled(!running && !exporting);
        batchBtn.setEnabled(!running && !exporting);
        hostField.setEnabled(!running && !exporting);
        customRangeField.setEnabled(!running && !exporting);
        previewBtn.setEnabled(!running);
        clearBtn.setEnabled(!running && !exporting);
        exportBtn.setEnabled(session != null && !running && !exporting);
        copyBtn.setEnabled(session != null && !exporting && !copying && !closed);
        importTargetsBtn.setEnabled(!running && !exporting && !closed);
        profileCombo.setEnabled(!running && !exporting && !closed);
        saveProfileBtn.setEnabled(!running && !exporting && !closed);
        deleteProfileBtn.setEnabled(
                !running && !exporting && !closed && profileCombo.getSelectedIndex() > 0);
        if (diagnoseBtn != null)
            diagnoseBtn.setEnabled(
                    !closed
                            && !running
                            && resultTable.getSelectedRow() >= 0
                            && resultTable.getSelectedRow() < visibleResults.size()
                            && visibleResults.get(resultTable.getSelectedRow()).state()
                                    == ProbeOutcome.State.OPEN);
        if (session == null) {
            previousBtn.setEnabled(false);
            nextBtn.setEnabled(false);
        }
    }

    private void showError(Exception error) {
        if (statusLabel != null) {
            statusLabel.setText(localize(error.getMessage()));
            statusLabel.setToolTipText(statusLabel.getText());
        }
        if (settingsStatusLabel != null) settingsStatusLabel.setText(localize(error.getMessage()));
    }

    private static String localize(String message) {
        if (message == null) return t("error.generic");
        String[] parts = message.split("\\|", 2);
        return parts[0].startsWith("portscan.")
                ? I18n.get(parts[0], parts.length == 2 ? parts[1] : "")
                : message;
    }

    private static void ioCleanup(ScanSession session) {
        CompletableFuture.runAsync(session::close);
    }

    @Override
    public void closeResources() {
        closed = true;
        generation++;
        cancelPreparation = true;
        if (timer != null) timer.stop();
        if (session != null) ioCleanup(session);
        io.shutdown();
        SwingUtilities.invokeLater(
                () -> {
                    if (settingsDialog != null) settingsDialog.dispose();
                    for (JDialog dialog : List.copyOf(diagnosticDialogs)) dialog.dispose();
                });
    }
}
