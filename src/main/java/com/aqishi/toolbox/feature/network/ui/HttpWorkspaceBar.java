package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.application.HttpWorkspaceStore;
import com.aqishi.toolbox.feature.network.domain.HttpWorkspace;
import com.aqishi.toolbox.feature.network.domain.HttpWorkspace.*;
import com.aqishi.toolbox.infra.secrets.SecretStore;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.*;
import java.util.function.*;

/** Collection actions on the EDT; vault writes remain asynchronous. No resolved request is persisted. */
final class HttpWorkspaceBar extends JPanel implements AutoCloseable {
    private final HttpWorkspaceStore store;
    private final Supplier<Request> capture;
    private final Consumer<Request> apply;
    private final JComboBox<Request> saved = new JComboBox<>();
    private final JComboBox<String> environment = new JComboBox<>();
    private final JLabel status = Fields.caption("");
    private JButton loadButton, saveButton, environmentButton, unlockButton, moreButton;
    private JMenuItem deleteItem, historyItem;
    private boolean closed;
    private final SecretStore.Listener listener = state -> SwingUtilities.invokeLater(this::refresh);

    HttpWorkspaceBar(SecretStore secrets, Supplier<Request> capture, Consumer<Request> apply) {
        super(new BorderLayout(0, Tokens.SPACE_XS));
        setOpaque(false);
        this.store = new HttpWorkspaceStore(secrets); this.capture = capture; this.apply = apply;
        saved.setMinimumSize(new Dimension(100, Tokens.CONTROL_HEIGHT));
        environment.setMinimumSize(new Dimension(100, Tokens.CONTROL_HEIGHT));
        saved.setToolTipText(I18n.get("ui.http.collectionHint"));
        loadButton = button("load", () -> { if (saved.getSelectedItem() instanceof Request r) apply.accept(r); });
        saveButton = button("save", this::save);
        moreButton = Buttons.snug(I18n.get("ui.actions.more"));
        JPopupMenu menu = new JPopupMenu();
        historyItem = menuItem("history", this::history); deleteItem = menuItem("delete", this::delete);
        menu.add(historyItem); menu.addSeparator(); menu.add(deleteItem);
        moreButton.addActionListener(e -> menu.show(moreButton, 0, moreButton.getHeight()));
        environmentButton = button("environment", this::editEnvironment);
        unlockButton = button("unlock", this::unlock);
        JPanel collection = selectorRow(I18n.get("ui.http.collection"), saved, loadButton, saveButton, moreButton);
        JPanel environments = selectorRow(I18n.get("ui.http.environment"), environment, environmentButton, button("preview", this::preview));
        JPanel footer = Layouts.box(Tokens.SPACE_SM, 0);
        status.setMinimumSize(new Dimension(80, Tokens.CONTROL_HEIGHT));
        footer.add(status, BorderLayout.CENTER); footer.add(unlockButton, BorderLayout.EAST);
        add(Layouts.stack(Tokens.SPACE_XS, collection, environments), BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        saved.addActionListener(e -> updateActions());
        secrets.addListener(listener); refresh();
    }
    private JPanel selectorRow(String text, JComboBox<?> combo, JButton... buttons) {
        JLabel label = Fields.label(text); label.setLabelFor(combo);
        JPanel row = Layouts.box(Tokens.SPACE_SM, 0);
        row.add(label, BorderLayout.WEST); row.add(combo, BorderLayout.CENTER);
        row.add(Layouts.wrapRow(Tokens.SPACE_XS, 0, buttons), BorderLayout.EAST);
        return row;
    }
    private JMenuItem menuItem(String key, Runnable action) {
        JMenuItem item = new JMenuItem(I18n.get("http.workspace." + key));
        item.addActionListener(e -> { try { action.run(); } catch (Exception error) { showError(error); } });
        return item;
    }
    private void updateActions() {
        if (loadButton == null) return;
        boolean unlocked = store.secrets().status() == SecretStore.Status.UNLOCKED;
        loadButton.setEnabled(unlocked && saved.getSelectedItem() != null);
        deleteItem.setEnabled(loadButton.isEnabled());
        saveButton.setEnabled(unlocked); environmentButton.setEnabled(unlocked);
        saved.setEnabled(unlocked && saved.getItemCount() > 0); environment.setEnabled(unlocked);
        moreButton.setEnabled(unlocked);
        unlockButton.setVisible(store.secrets().status() == SecretStore.Status.LOCKED);
        status.setToolTipText(I18n.get(unlocked ? "http.workspace.encrypted" : "http.workspace.unlockRequired"));
        saveButton.setToolTipText(unlocked ? null : I18n.get("http.workspace.unlockRequired"));
    }
    private JButton button(String key, Runnable action) {
        JButton button = Buttons.secondary(I18n.get("http.workspace." + key));
        Dimension natural = button.getPreferredSize();
        button.setPreferredSize(new Dimension(Math.max(84, natural.width + 8), natural.height));
        button.addActionListener(e -> { try { action.run(); } catch (Exception error) { showError(error); } });
        return button;
    }
    Request resolve(Request request, boolean redacted) {
        String selected = (String) environment.getSelectedItem();
        Environment env = null;
        if (selected != null && !selected.equals(I18n.get("http.workspace.noEnvironment"))) {
            env = store.load().environments().stream().filter(e -> e.name().equals(selected)).findFirst().orElseThrow();
        }
        return HttpWorkspace.resolve(request, env, redacted);
    }
    UnaryOperator<String> errorRedactor() {
        String selected = (String) environment.getSelectedItem();
        java.util.List<String> values = new ArrayList<>();
        if (store.secrets().status() == SecretStore.Status.UNLOCKED) {
            store.load().environments().stream().filter(e -> e.name().equals(selected)).forEach(e ->
                    e.variables().values().stream().filter(v -> v.secret() && !v.value().isEmpty()).forEach(v -> values.add(v.value())));
        }
        values.sort(Comparator.comparingInt(String::length).reversed());
        return error -> { String safe = error; for (String value : values) safe = safe.replace(value, "******"); return safe; };
    }
    void remember(Request request) {
        if (store.secrets().status() == SecretStore.Status.UNLOCKED) write(d -> d.remember(request));
    }
    private void write(UnaryOperator<Document> edit) {
        store.edit(edit).whenComplete((ok, error) -> SwingUtilities.invokeLater(() -> {
            if (closed) return;
            if (error != null) showError(error); else refresh();
        }));
    }
    private void refresh() {
        if (closed) return;
        String name = saved.getSelectedItem() instanceof Request r ? r.name() : "";
        String env = (String) environment.getSelectedItem();
        saved.removeAllItems(); environment.removeAllItems(); environment.addItem(I18n.get("http.workspace.noEnvironment"));
        status.setText(I18n.get(store.secrets().status() == SecretStore.Status.LOCKED ? "ui.http.locked" : "ui.http.noVault"));
        updateActions();
        if (store.secrets().status() != SecretStore.Status.UNLOCKED) return;
        try {
            Document doc = store.load();
            doc.requests().stream().sorted(Comparator.comparing(Request::favorite).reversed().thenComparing(Request::name)).forEach(saved::addItem);
            for (int i = 0; i < saved.getItemCount(); i++) if (saved.getItemAt(i).name().equals(name)) saved.setSelectedIndex(i);
            doc.environments().forEach(e -> environment.addItem(e.name()));
            if (doc.environments().stream().anyMatch(e -> e.name().equals(env))) environment.setSelectedItem(env);
            historyItem.setEnabled(!doc.history().isEmpty());
            status.setText(I18n.get("ui.http.savedStatus", doc.requests().size(), doc.environments().size()));
        } catch (Exception error) { status.setText(Errors.describeRoot(error)); }
        updateActions();
    }
    private void save() {
        store.load();
        Request request = capture.get();
        JTextField name = Fields.text(request.name()); JCheckBox favorite = Fields.check(I18n.get("http.workspace.favorite"), request.favorite());
        FormGrid form = new FormGrid(); form.row(I18n.get("ui.http.requestName"), name); form.fullRow(favorite);
        if (JOptionPane.showConfirmDialog(this, form, I18n.get("http.workspace.save"), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        String label = name.getText().trim();
        if (store.load().requests().stream().anyMatch(r -> r.name().equals(label)) && !confirm("overwrite")) return;
        Request named = new Request(label, request.method(), request.url(), request.headers(), request.body(), favorite.isSelected());
        apply.accept(named);
        write(d -> d.save(named));
    }
    private void delete() {
        if (saved.getSelectedItem() instanceof Request r && confirm("deleteConfirm")) write(d -> d.deleteRequest(r.name()));
    }
    private void history() {
        java.util.List<Request> entries = store.load().history();
        JList<Request> list = new JList<>(entries.toArray(Request[]::new));
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                Request r = (Request) value;
                // URLs can contain credentials; show only the template name and method here.
                return super.getListCellRendererComponent(l, r.method() + " " + (r.name().isBlank() ? "#" + (index + 1) : r.name()), index, selected, focus);
            }
        });
        Object[] actions = {I18n.get("http.workspace.load"), I18n.get("http.workspace.clearHistory"), I18n.get("vault.cancel")};
        int chosen = JOptionPane.showOptionDialog(this, new JScrollPane(list), I18n.get("http.workspace.history"),
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, actions, actions[0]);
        if (chosen == 0 && list.getSelectedValue() != null) apply.accept(list.getSelectedValue());
        if (chosen == 1 && confirm("deleteConfirm")) write(d -> new Document(1, d.requests(), d.environments(), java.util.List.of()));
    }
    private void editEnvironment() {
        Document doc = store.load(); String selected = (String) environment.getSelectedItem();
        Environment old = doc.environments().stream().filter(e -> e.name().equals(selected)).findFirst().orElse(null);
        JTextField name = Fields.text(old == null ? "dev" : old.name());
        DefaultTableModel model = new DefaultTableModel(new Object[]{I18n.get("http.workspace.variable"), I18n.get("http.workspace.value"), I18n.get("http.workspace.secret")}, 0) {
            @Override public Class<?> getColumnClass(int column) { return column == 2 ? Boolean.class : String.class; }
        };
        if (old != null) old.variables().forEach((k,v) -> model.addRow(new Object[]{k,v.value(),v.secret()}));
        JTable table = new JTable(model);
        table.setRowHeight(Tokens.CONTROL_HEIGHT); table.setFillsViewportHeight(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(160);
        table.getColumnModel().getColumn(1).setPreferredWidth(250);
        table.getColumnModel().getColumn(2).setPreferredWidth(145);
        JCheckBox reveal = Fields.check(I18n.get("ui.http.reveal"), false);
        table.getColumnModel().getColumn(1).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int col) {
                Object shown = !reveal.isSelected() && Boolean.TRUE.equals(model.getValueAt(row, 2)) ? "••••••" : value;
                return super.getTableCellRendererComponent(t, shown, selected, focus, row, col);
            }
        });
        reveal.addActionListener(e -> table.repaint());
        JButton add = Buttons.snug(I18n.get("ui.http.addVariable"));
        add.addActionListener(e -> { model.addRow(new Object[]{"", "", false}); int row = model.getRowCount()-1; table.setRowSelectionInterval(row,row); table.editCellAt(row,0); table.getEditorComponent().requestFocusInWindow(); });
        JButton remove = Buttons.snug(I18n.get("ui.http.removeVariable")); remove.setEnabled(false);
        table.getSelectionModel().addListSelectionListener(e -> remove.setEnabled(table.getSelectedRow() >= 0));
        remove.addActionListener(e -> { if (table.isEditing()) table.getCellEditor().cancelCellEditing(); int i=table.getSelectedRow(); if(i>=0) model.removeRow(i); });
        JPanel buttons = Layouts.wrapRow(add, remove, reveal);
        JPanel form = Layouts.box(0, Tokens.SPACE_SM);
        FormGrid heading = new FormGrid(); heading.row(I18n.get("ui.http.environmentName"),name);
        form.add(heading,BorderLayout.NORTH); form.add(Fields.scrollBoxed(table));
        form.add(Layouts.stack(Tokens.SPACE_XS, buttons, Fields.note(I18n.get("ui.http.variableHint"))),BorderLayout.SOUTH);
        form.setPreferredSize(new Dimension(600,330));
        Object[] actions = {I18n.get("ui.http.saveEnvironment"), I18n.get("http.workspace.delete"), I18n.get("vault.cancel")};
        int choice = JOptionPane.showOptionDialog(this, form, I18n.get("http.workspace.environment"), JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, actions, actions[0]);
        if (choice == 1 && old != null && confirm("deleteConfirm")) { write(d -> d.deleteEnvironment(old.name())); return; }
        if (choice != 0) return;
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        Map<String,Variable> vars = new LinkedHashMap<>();
        for (int i=0;i<model.getRowCount();i++) {
            String key=Objects.toString(model.getValueAt(i,0), "").trim();
            if (key.isEmpty() && Objects.toString(model.getValueAt(i,1), "").isEmpty()) continue;
            if (vars.put(key,new Variable(Objects.toString(model.getValueAt(i,1), ""),Boolean.TRUE.equals(model.getValueAt(i,2)))) != null)
                throw new IllegalArgumentException(I18n.get("http.workspace.duplicate"));
        }
        Environment next = new Environment(name.getText().trim(), vars);
        if ((old == null || !old.name().equals(next.name())) && doc.environments().stream().anyMatch(e -> e.name().equals(next.name())) && !confirm("overwrite")) return;
        write(d -> d.environment(next));
    }
    private void preview() {
        Request r = resolve(capture.get(), true);
        JTextArea output = Fields.output(14,65); output.setText(r.method()+" "+r.url()+"\n\n"+r.headers()+"\n\n"+r.body());
        JOptionPane.showMessageDialog(this, new JScrollPane(output), I18n.get("http.workspace.preview"), JOptionPane.PLAIN_MESSAGE);
    }
    private void unlock() {
        if (store.secrets().status() != SecretStore.Status.LOCKED) { refresh(); return; }
        JPasswordField password = Fields.password();
        if (JOptionPane.showConfirmDialog(this,password,I18n.get("vault.unlock"),JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        char[] value = password.getPassword(); password.setText("");
        store.secrets().unlock(value).whenComplete((ok,error) -> SwingUtilities.invokeLater(() -> { if(!closed) { if(error!=null) showError(error); else refresh(); } }));
    }
    private boolean confirm(String key) { return JOptionPane.showConfirmDialog(this,I18n.get("http.workspace."+key),I18n.get("http.workspace.title"),JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION; }
    private void showError(Throwable error) { UIUtils.error(this, Errors.describeRoot(error)); }
    @Override public void close() { closed=true; store.secrets().removeListener(listener); saved.removeAllItems(); environment.removeAllItems(); }
}
