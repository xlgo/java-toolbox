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
    private boolean closed;
    private final SecretStore.Listener listener = state -> SwingUtilities.invokeLater(this::refresh);

    HttpWorkspaceBar(SecretStore secrets, Supplier<Request> capture, Consumer<Request> apply) {
        super(new BorderLayout(0, Tokens.SPACE_XS));
        setOpaque(false);
        this.store = new HttpWorkspaceStore(secrets); this.capture = capture; this.apply = apply;
        JPanel row = Layouts.wrapRow(Tokens.SPACE_XS, Tokens.SPACE_XS); row.setOpaque(false);
        saved.setPreferredSize(new Dimension(170, Tokens.CONTROL_HEIGHT));
        environment.setPreferredSize(new Dimension(120, Tokens.CONTROL_HEIGHT));
        row.add(saved); row.add(button("load", () -> { if (saved.getSelectedItem() instanceof Request r) apply.accept(r); }));
        row.add(button("save", this::save)); row.add(button("delete", this::delete));
        row.add(button("history", this::history)); row.add(environment);
        row.add(button("environment", this::editEnvironment)); row.add(button("preview", this::preview));
        row.add(button("unlock", this::unlock));
        add(row, BorderLayout.CENTER); add(status, BorderLayout.SOUTH);
        secrets.addListener(listener); refresh();
    }
    private JButton button(String key, Runnable action) {
        JButton button = Buttons.secondary(I18n.get("http.workspace." + key));
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
        status.setText(I18n.get("http.workspace.unlockRequired"));
        if (store.secrets().status() != SecretStore.Status.UNLOCKED) return;
        try {
            Document doc = store.load();
            doc.requests().stream().sorted(Comparator.comparing(Request::favorite).reversed().thenComparing(Request::name)).forEach(saved::addItem);
            for (int i = 0; i < saved.getItemCount(); i++) if (saved.getItemAt(i).name().equals(name)) saved.setSelectedIndex(i);
            doc.environments().forEach(e -> environment.addItem(e.name()));
            if (doc.environments().stream().anyMatch(e -> e.name().equals(env))) environment.setSelectedItem(env);
            status.setText(I18n.get("http.workspace.encrypted"));
        } catch (Exception error) { status.setText(Errors.describeRoot(error)); }
    }
    private void save() {
        store.load();
        Request request = capture.get();
        JTextField name = Fields.text(request.name()); JCheckBox favorite = Fields.check(I18n.get("http.workspace.favorite"), request.favorite());
        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4)); form.add(name); form.add(favorite);
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
        JButton add = Buttons.secondary("+"); add.addActionListener(e -> model.addRow(new Object[]{"", "", false}));
        JButton remove = Buttons.secondary("−"); remove.addActionListener(e -> { int i=table.getSelectedRow(); if(i>=0) model.removeRow(i); });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT)); buttons.add(add); buttons.add(remove);
        JPanel form = new JPanel(new BorderLayout(4,4)); form.add(name,BorderLayout.NORTH); form.add(new JScrollPane(table)); form.add(buttons,BorderLayout.SOUTH);
        form.setPreferredSize(new Dimension(570,300));
        Object[] actions = {I18n.get("http.workspace.save"), I18n.get("http.workspace.delete"), I18n.get("vault.cancel")};
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
