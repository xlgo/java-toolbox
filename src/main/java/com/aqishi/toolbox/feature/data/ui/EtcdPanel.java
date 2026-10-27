package com.aqishi.toolbox.feature.data.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.data.application.EtcdClient;
import com.aqishi.toolbox.feature.data.domain.EtcdModels;
import com.aqishi.toolbox.feature.data.domain.EtcdModels.*;
import com.aqishi.toolbox.feature.network.ssh.domain.SshConnectionConfig;
import com.aqishi.toolbox.feature.network.ssh.infra.SshConfigStore;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Session-scoped etcd browser. Every network operation runs off the Swing event thread. */
public class EtcdPanel extends ToolPanel implements ManagedResourceOwner {
    private JTextField endpointField, usernameField, caField, certField, privateKeyField, queryField, keyField;
    private JPasswordField passwordField;
    private JComboBox<Api> apiCombo;
    private JComboBox<Encoding> keyEncoding, valueEncoding;
    private JComboBox<SshConnectionConfig> sshCombo;
    private JCheckBox useSshCheck, directoryCheck;
    private JSpinner timeoutSpinner, limitSpinner, ttlSpinner;
    private JButton connectBtn, settingsBtn, disconnectBtn, refreshBtn, moreBtn, parentBtn, readBtn, newBtn, saveBtn, deleteBtn, cancelBtn;
    private JLabel statusLabel, countLabel, metadataLabel;
    private JPanel settingsBody;
    private JDialog settingsDialog;
    private JTable table;
    private DefaultTableModel model;
    private JTextArea valueArea;
    private final List<Entry> entries = new ArrayList<>();
    private Entry loaded;
    private Encoding previousKeyEncoding = Encoding.UTF8, previousValueEncoding = Encoding.UTF8;
    private boolean changingEncoding;
    private byte[] nextKey, activePrefix;
    private long revision;
    private long cachedBytes;
    private boolean more;
    private volatile boolean closed, busy;
    private Card connectionCard;
    private volatile EtcdClient client;
    private final AtomicReference<EtcdClient> opening = new AtomicReference<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(DaemonThreads.factory("etcd-request"));
    private final AtomicReference<Thread> requestThread = new AtomicReference<>();
    private volatile boolean cancelRequested;
    private final Runnable sshChanges = this::refreshSshConfigs;

    public EtcdPanel() { super(ToolCatalog.ETCD_MANAGEMENT); }
    @Override protected JComponent build() {
        JPanel root = Layouts.page();
        root.add(connectionCard(), BorderLayout.NORTH);
        root.add(Layouts.splitHorizontal(browserCard(), editorCard(), .46, .46), BorderLayout.CENTER);
        statusLabel = Fields.caption(I18n.get("etcd.disconnected"));
        root.add(statusLabel, BorderLayout.SOUTH);
        buildSettings();
        SshConfigStore.getInstance().addChangeListener(sshChanges);
        refreshSshConfigs(); updateActions();
        return root;
    }
    private Card connectionCard() {
        endpointField = Fields.mono("http://127.0.0.1:2379");
        apiCombo = Fields.combo(Api.values(), 80);
        apiCombo.addActionListener(e -> {
            queryField.setText(api() == Api.V2 ? "/" : ""); resetEditor(); updateActions();
        });
        connectBtn = Buttons.primary(I18n.get("etcd.connect")); connectBtn.addActionListener(e -> connect());
        disconnectBtn = Buttons.secondary(I18n.get("etcd.disconnect")); disconnectBtn.addActionListener(e -> disconnect());
        settingsBtn = Buttons.secondary(I18n.get("etcd.settings")); settingsBtn.addActionListener(e -> showSettings());
        cancelBtn = Buttons.danger(I18n.get("etcd.cancel")); cancelBtn.addActionListener(e -> cancel());
        useSshCheck = Fields.check(I18n.get("etcd.ssh"), false);
        List<SshConnectionConfig> configs = SshConfigStore.getInstance().getAll();
        sshCombo = Fields.combo(configs.toArray(new SshConnectionConfig[0]));
        sshCombo.setEnabled(false);
        useSshCheck.addActionListener(e -> updateActions());
        JPanel sshRow = Layouts.box(Tokens.SPACE_MD, 0);
        sshRow.add(useSshCheck, BorderLayout.WEST);
        sshRow.add(sshCombo, BorderLayout.CENTER);
        sshCombo.setToolTipText(I18n.get("etcd.sshHint"));
        JPanel line = Layouts.box(Tokens.SPACE_MD, 0);
        line.add(endpointField, BorderLayout.CENTER);
        line.add(Layouts.wrapRow(apiCombo, settingsBtn, connectBtn, disconnectBtn, cancelBtn), BorderLayout.EAST);
        FormGrid form = new FormGrid();
        form.row(I18n.get("etcd.endpoint"), line);
        form.row(I18n.get("etcd.sshLabel"), sshRow);
        form.fullRow(Fields.note(I18n.get("etcd.sshHint")));
        connectionCard = Card.titled(I18n.get("tool.etcd.management")).setCollapsible(true); connectionCard.setContent(form); return connectionCard;
    }
    private void buildSettings() {
        usernameField = Fields.text(""); passwordField = new JPasswordField();
        timeoutSpinner = Fields.spinner(10000, 1000, 60000, 1000);
        caField = Fields.text(""); certField = Fields.text(""); privateKeyField = Fields.text("");
        FormGrid form = new FormGrid();
        form.row(I18n.get("etcd.username"), usernameField);
        form.row(I18n.get("etcd.password"), passwordField);
        form.row(I18n.get("etcd.timeout"), timeoutSpinner);
        form.row(I18n.get("etcd.ca"), filePicker(caField));
        form.row(I18n.get("etcd.cert"), filePicker(certField));
        form.row(I18n.get("etcd.privateKey"), filePicker(privateKeyField));
        form.fullRow(Fields.note(I18n.get("etcd.settingsHint")));
        settingsBody = new SettingsViewport(); settingsBody.add(form, BorderLayout.NORTH);
    }
    private JPanel filePicker(JTextField field) {
        JButton browse = Buttons.snug(I18n.get("etcd.browse"));
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            if (chooser.showOpenDialog(settingsDialog) == JFileChooser.APPROVE_OPTION)
                field.setText(chooser.getSelectedFile().getAbsolutePath());
        });
        JPanel row = Layouts.box(Tokens.SPACE_SM, 0); row.add(field, BorderLayout.CENTER); row.add(browse, BorderLayout.EAST); return row;
    }
    private void showSettings() {
        if (busy || client != null || closed) return;
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(getView()), I18n.get("etcd.settings"), Dialog.ModalityType.APPLICATION_MODAL);
        settingsDialog = dialog;
        JPanel body = Layouts.page(); body.add(Fields.scroll(settingsBody), BorderLayout.CENTER);
        JButton done = Buttons.primary(I18n.get("etcd.done")); done.addActionListener(e -> dialog.dispose());
        body.add(Layouts.wrapRow(done), BorderLayout.SOUTH); dialog.setContentPane(body);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        dialog.setSize(Math.min(800, screen.width), Math.min(620, screen.height)); dialog.setLocationRelativeTo(getView());
        try { dialog.setVisible(true); } finally { dialog.dispose(); settingsDialog = null; }
    }
    private Card browserCard() {
        queryField = Fields.mono(""); limitSpinner = Fields.spinner(200, 1, 10000, 100);
        refreshBtn = Buttons.primary(I18n.get("etcd.load")); refreshBtn.addActionListener(e -> load(false));
        parentBtn = Buttons.secondary(I18n.get("etcd.parent")); parentBtn.addActionListener(e -> {
            String path = queryField.getText();
            if (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
            int slash = path.lastIndexOf('/'); queryField.setText(slash <= 0 ? "/" : path.substring(0, slash)); load(false);
        });
        queryField.addActionListener(e -> load(false));
        JPanel search = Layouts.box(0, Tokens.SPACE_SM);
        search.add(queryField, BorderLayout.CENTER);
        search.add(Layouts.wrapRow(Fields.caption(I18n.get("etcd.limit")), limitSpinner, refreshBtn, parentBtn), BorderLayout.SOUTH);
        model = new DefaultTableModel(new Object[]{I18n.get("etcd.key"), I18n.get("etcd.type"), I18n.get("etcd.revision")}, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        table = new JTable(model); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(290);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !busy && table.getSelectedRow() >= 0)
                showEntry(entries.get(table.convertRowIndexToModel(table.getSelectedRow())));
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && !busy && loaded != null && loaded.directory()) {
                    queryField.setText(loaded.label()); load(false);
                }
            }
        });
        moreBtn = Buttons.secondary(I18n.get("etcd.more")); moreBtn.addActionListener(e -> load(true));
        countLabel = Fields.caption(I18n.get("etcd.count", 0));
        JPanel content = Layouts.box(0, Tokens.SPACE_SM); content.add(search, BorderLayout.NORTH); content.add(Fields.scroll(table), BorderLayout.CENTER);
        Card card = Card.titled(I18n.get("etcd.browser")); card.setContent(content);
        card.setFooter(Layouts.wrapRow(countLabel, moreBtn)); return card;
    }
    private Card editorCard() {
        keyField = Fields.mono(""); keyEncoding = Fields.combo(Encoding.values(), 120); valueEncoding = Fields.combo(Encoding.values(), 120);
        keyEncoding.addActionListener(e -> changeEncoding(true)); valueEncoding.addActionListener(e -> changeEncoding(false));
        readBtn = Buttons.secondary(I18n.get("etcd.read")); readBtn.addActionListener(e -> read());
        newBtn = Buttons.secondary(I18n.get("etcd.new")); newBtn.addActionListener(e -> { table.clearSelection(); resetEditor(); updateActions(); });
        saveBtn = Buttons.primary(I18n.get("etcd.save")); saveBtn.addActionListener(e -> save());
        deleteBtn = Buttons.danger(I18n.get("etcd.delete")); deleteBtn.addActionListener(e -> delete());
        directoryCheck = Fields.check(I18n.get("etcd.directory"), false); directoryCheck.addActionListener(e -> updateActions());
        ttlSpinner = Fields.spinner(-1, -1, 31536000, 1);
        valueArea = Fields.area(16, 45); metadataLabel = Fields.caption(I18n.get("etcd.editorHint"));
        FormGrid form = new FormGrid();
        form.row(I18n.get("etcd.key"), keyField);
        form.row(I18n.get("etcd.keyEncoding"), Layouts.wrapRow(keyEncoding, readBtn));
        form.row(I18n.get("etcd.valueEncoding"), Layouts.wrapRow(valueEncoding, directoryCheck));
        form.row(I18n.get("etcd.ttl"), ttlSpinner);
        form.fullRow(Fields.note(I18n.get("etcd.ttlHint")));
        JPanel content = Layouts.box(0, Tokens.SPACE_SM); content.add(form, BorderLayout.NORTH);
        content.add(Fields.scroll(valueArea), BorderLayout.CENTER);
        Card card = Card.titled(I18n.get("etcd.editor")); card.setContent(content);
        card.addHeaderAction(newBtn); card.addHeaderAction(deleteBtn); card.addHeaderAction(saveBtn); card.setFooter(metadataLabel); return card;
    }

    protected EtcdClient openClient(Options options) throws Exception { return EtcdClient.open(options); }
    private void connect() {
        if (busy || client != null || closed) return;
        try {
            SshConnectionConfig ssh = (SshConnectionConfig) sshCombo.getSelectedItem();
            if (useSshCheck.isSelected() && ssh == null) throw new IllegalArgumentException("etcd.error.ssh");
            char[] password = passwordField.getPassword();
            Options options;
            try {
                options = new Options(URI.create(endpointField.getText().trim()), api(), usernameField.getText(), new String(password),
                        ((Number)timeoutSpinner.getValue()).intValue(), caField.getText(), certField.getText(), privateKeyField.getText(),
                        useSshCheck.isSelected() ? ssh.getId() : "");
            } finally { Arrays.fill(password, '\0'); }
            byte[] prefix = queryPrefix(); int limit = ((Number)limitSpinner.getValue()).intValue();
            run(() -> {
                EtcdClient candidate = openClient(options); opening.set(candidate);
                try {
                    if (closed || cancelRequested || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    String version = candidate.connect(); Page page = candidate.list(prefix, limit, null, 0);
                    if (closed || cancelRequested || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    return new Connected(candidate, page, version);
                } catch (Exception error) { candidate.close(); throw error; }
                finally { opening.compareAndSet(candidate, null); }
            }, value -> {
                client = value.client(); activePrefix = prefix; if (connectionCard != null && !connectionCard.isCollapsed()) connectionCard.setCollapsed(true);
                install(value.page(), false);
                statusLabel.setText(options.sshConfigId().isEmpty()
                        ? I18n.get("etcd.connected", value.version(), options.api())
                        : I18n.get("etcd.connectedSsh", value.version(), options.api(), ssh.getName()));
            }, false);
        } catch (Exception error) { showError(error, false); }
    }
    private void load(boolean append) {
        if (busy || client == null) return;
        try {
            byte[] prefix = append ? activePrefix.clone() : queryPrefix();
            int limit = ((Number)limitSpinner.getValue()).intValue();
            if (append && api() == Api.V2) { limit = Math.min(10000, entries.size() + limit); append = false; }
            final boolean add = append;
            byte[] start = add ? nextKey : null; long snapshot = add ? revision : 0;
            int pageLimit = limit; EtcdClient current = client;
            run(() -> current.list(prefix, pageLimit, start, snapshot), page -> {
                activePrefix = prefix; install(page, add);
                statusLabel.setText(I18n.get("etcd.loaded", entries.size()));
            }, false);
        } catch (Exception error) { showError(error, false); }
    }
    private void read() {
        if (busy || client == null) return;
        try {
            byte[] key = editorKey(); EtcdClient current = client;
            run(() -> current.get(key), entry -> {
                if (entry == null) { resetEditor(); statusLabel.setText(I18n.get("etcd.notFound")); }
                else { showEntry(entry); statusLabel.setText(I18n.get("etcd.readDone")); }
            }, false);
        } catch (Exception error) { showError(error, false); }
    }
    private void save() {
        if (busy || client == null) return;
        try {
            byte[] key = editorKey(), value = EtcdModels.parse(valueArea.getText(), (Encoding)valueEncoding.getSelectedItem());
            long expected = loaded == null ? 0 : loaded.modified();
            long ttl = ((Number)ttlSpinner.getValue()).longValue(); boolean directory = directoryCheck.isSelected();
            if (loaded != null && !Arrays.equals(loaded.key(), key)) throw new IllegalArgumentException("etcd.error.changedKey");
            EtcdClient current = client;
            run(() -> { current.put(key, value, expected, ttl, directory); return current.get(key); }, entry -> {
                if (entry == null) resetEditor(); else showEntry(entry);
                statusLabel.setText(I18n.get("etcd.saved"));
            }, true);
        } catch (Exception error) { showError(error, false); }
    }
    private void delete() {
        if (busy || client == null || loaded == null || loaded.directory()) return;
        if (!UIUtils.confirm(getView(), I18n.get("etcd.deleteConfirm", loaded.label()), I18n.get("etcd.delete"))) return;
        Entry selected = loaded; EtcdClient current = client;
        run(() -> { current.delete(selected); return selected; }, entry -> {
            entries.removeIf(value -> Arrays.equals(value.key(), entry.key())); renderEntries(); resetEditor();
            statusLabel.setText(I18n.get("etcd.deleted"));
        }, true);
    }
    private void install(Page page, boolean append) {
        long bytes = page.entries().stream().mapToLong(Entry::byteSize).sum() + (append ? cachedBytes : 0);
        if (bytes > 32L * 1024 * 1024) throw new IllegalArgumentException(I18n.get("etcd.error.cacheLimit"));
        if (!append) { entries.clear(); resetEditor(); }
        cachedBytes = bytes;
        entries.addAll(page.entries()); nextKey = page.nextKey(); revision = page.revision(); more = page.more(); renderEntries();
    }
    private void renderEntries() {
        model.setRowCount(0);
        for (Entry entry : entries) model.addRow(new Object[]{entry.label(), entry.directory() ? I18n.get("etcd.directory") : "KV", entry.modified()});
        countLabel.setText(I18n.get("etcd.count", entries.size()));
    }
    private void showEntry(Entry entry) {
        loaded = entry; changingEncoding = true;
        try {
            try { keyField.setText(EtcdModels.encode(entry.key(), previousKeyEncoding)); }
            catch (IllegalArgumentException binary) { previousKeyEncoding = Encoding.BASE64; keyField.setText(EtcdModels.encode(entry.key(), previousKeyEncoding)); }
            try { valueArea.setText(EtcdModels.encode(entry.value(), previousValueEncoding)); }
            catch (IllegalArgumentException binary) { previousValueEncoding = Encoding.BASE64; valueArea.setText(EtcdModels.encode(entry.value(), previousValueEncoding)); }
            keyEncoding.setSelectedItem(previousKeyEncoding); valueEncoding.setSelectedItem(previousValueEncoding);
            directoryCheck.setSelected(entry.directory()); ttlSpinner.setValue(-1);
            metadataLabel.setText(I18n.get("etcd.metadata", entry.created(), entry.modified(), entry.version(), Long.toString(entry.lease()), entry.ttl()));
            valueArea.setCaretPosition(0);
        } finally { changingEncoding = false; }
        updateActions();
    }
    private void resetEditor() {
        loaded = null;
        if (keyField == null) return;
        keyField.setText(""); valueArea.setText(""); directoryCheck.setSelected(false); ttlSpinner.setValue(-1);
        metadataLabel.setText(I18n.get("etcd.editorHint"));
    }
    private void changeEncoding(boolean key) {
        if (changingEncoding || valueArea == null) return;
        JComboBox<Encoding> combo = key ? keyEncoding : valueEncoding;
        Encoding old = key ? previousKeyEncoding : previousValueEncoding, next = (Encoding)combo.getSelectedItem();
        try {
            String text = key ? keyField.getText() : valueArea.getText();
            String converted = EtcdModels.encode(EtcdModels.parse(text, old), next);
            if (key) { keyField.setText(converted); previousKeyEncoding = next; }
            else { valueArea.setText(converted); previousValueEncoding = next; }
        } catch (Exception error) {
            changingEncoding = true; combo.setSelectedItem(old); changingEncoding = false; showError(error, false);
        }
    }

    private <T> void run(Callable<T> work, java.util.function.Consumer<T> apply, boolean mutation) {
        if (busy || closed) return;
        busy = true; cancelRequested = false; updateActions(); statusLabel.setText(I18n.get("etcd.working"));
        executor.execute(() -> {
            T result = null; Exception failure = null;
            requestThread.set(Thread.currentThread());
            try {
                if (closed || cancelRequested) throw new InterruptedException();
                result = work.call();
                if (closed || cancelRequested) {
                    if (result instanceof Connected connection) connection.client().close();
                    result = null;
                    throw new InterruptedException();
                }
            } catch (Exception error) { failure = error; }
            finally { requestThread.set(null); Thread.interrupted(); }
            T completed = result; Exception error = failure;
            SwingUtilities.invokeLater(() -> {
                busy = false;
                if (closed || cancelRequested) {
                    if (completed instanceof Connected connection) CompletableFuture.runAsync(connection.client()::close);
                    if (!closed) { showError(new InterruptedException(), mutation); updateActions(); }
                    return;
                }
                try { if (error == null) apply.accept(completed); else showError(error, mutation); }
                catch (Exception failureInView) { showError(failureInView, mutation); }
                finally { updateActions(); }
            });
        });
    }
    private void cancel() {
        cancelRequested = true;
        Thread current = requestThread.get(); if (current != null) current.interrupt();
        EtcdClient candidate = opening.get(), connected = client;
        CompletableFuture.runAsync(() -> { if (candidate != null) candidate.cancel(); if (connected != null) connected.cancel(); });
        statusLabel.setText(I18n.get("etcd.stopping"));
    }
    private void disconnect() {
        if (busy) return;
        EtcdClient current = client; client = null;
        if (current != null) executor.execute(current::close);
        entries.clear(); more = false; nextKey = null; revision = 0; cachedBytes = 0; activePrefix = null; renderEntries(); resetEditor();
        statusLabel.setText(I18n.get("etcd.disconnected")); updateActions();
    }
    private void updateActions() {
        if (connectBtn == null || saveBtn == null || sshCombo == null) return;
        boolean idle = !closed && !busy, connected = client != null;
        connectBtn.setEnabled(idle && !connected); disconnectBtn.setEnabled(idle && connected);
        settingsBtn.setEnabled(idle && !connected); endpointField.setEnabled(idle && !connected); apiCombo.setEnabled(idle && !connected);
        useSshCheck.setEnabled(idle && !connected);
        cancelBtn.setEnabled(!closed && busy); refreshBtn.setEnabled(idle && connected); readBtn.setEnabled(idle && connected);
        parentBtn.setEnabled(idle && connected && api() == Api.V2);
        moreBtn.setEnabled(idle && connected && more && (api() == Api.V3 || entries.size() < 10000) && entries.size() < 100000);
        newBtn.setEnabled(idle && connected); saveBtn.setEnabled(idle && connected && (loaded == null || !loaded.directory()));
        deleteBtn.setEnabled(idle && connected && loaded != null && !loaded.directory());
        table.setEnabled(idle && connected); queryField.setEnabled(idle); limitSpinner.setEnabled(idle);
        keyField.setEditable(idle && connected && loaded == null);
        valueArea.setEditable(idle && connected && !directoryCheck.isSelected());
        keyEncoding.setEnabled(idle && connected); valueEncoding.setEnabled(idle && connected);
        directoryCheck.setEnabled(idle && connected && api() == Api.V2 && loaded == null);
        ttlSpinner.setEnabled(idle && connected && (loaded == null || !loaded.directory()));
        sshCombo.setEnabled(idle && !connected && useSshCheck.isSelected());
    }
    private void refreshSshConfigs() {
        SwingUtilities.invokeLater(() -> {
            if (closed || sshCombo == null) return;
            SshConnectionConfig selected = (SshConnectionConfig)sshCombo.getSelectedItem();
            sshCombo.removeAllItems();
            for (SshConnectionConfig config : SshConfigStore.getInstance().getAll()) {
                sshCombo.addItem(config); if (selected != null && selected.getId().equals(config.getId())) sshCombo.setSelectedItem(config);
            }
            updateActions();
        });
    }
    private Api api() { return (Api)apiCombo.getSelectedItem(); }
    private byte[] queryPrefix() { return queryField.getText().getBytes(StandardCharsets.UTF_8); }
    private byte[] editorKey() {
        byte[] key = EtcdModels.parse(keyField.getText(), (Encoding)keyEncoding.getSelectedItem());
        if (key.length == 0) throw new IllegalArgumentException("etcd.error.key");
        return key;
    }
    private void showError(Exception error, boolean mutation) {
        String message = error.getMessage();
        if (message == null || message.isBlank() || error instanceof InterruptedException || error instanceof java.io.InterruptedIOException)
            message = I18n.get("etcd.error.cancelled");
        else if (message.startsWith("etcd.")) message = I18n.get(message);
        statusLabel.setText((mutation ? I18n.get("etcd.error.mutation") + " " : "") + message);
        statusLabel.setToolTipText(statusLabel.getText());
    }
    @Override public void closeResources() {
        if (closed) return;
        closed = true;
        SshConfigStore.getInstance().removeChangeListener(sshChanges);
        cancelRequested = true;
        Thread current = requestThread.get(); if (current != null) current.interrupt();
        EtcdClient candidate = opening.get(), connected = client; client = null;
        CompletableFuture.runAsync(() -> { if (candidate != null) candidate.cancel(); if (connected != null) connected.close(); });
        executor.shutdown();
        SwingUtilities.invokeLater(() -> { if (passwordField != null) passwordField.setText(""); if (settingsDialog != null) settingsDialog.dispose(); });
    }
    private record Connected(EtcdClient client, Page page, String version) { }

    /** Keep the form within the dialog width so certificate pickers remain visible. */
    private static final class SettingsViewport extends JPanel implements Scrollable {
        SettingsViewport() {
            super(new BorderLayout()); setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(Tokens.SPACE_LG, Tokens.SPACE_LG, Tokens.SPACE_LG, Tokens.SPACE_LG));
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(16, visible.height - 16); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
