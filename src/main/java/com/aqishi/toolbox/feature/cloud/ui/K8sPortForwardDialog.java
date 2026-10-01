package com.aqishi.toolbox.feature.cloud.ui;

import com.aqishi.toolbox.feature.cloud.application.*;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;

/** A connection-scoped session manager. Closing the window stops all its forwards. */
final class K8sPortForwardDialog implements AutoCloseable {
    private final JDialog dialog;
    private final java.util.List<KubectlPortForward> sessions = new ArrayList<>();
    private final PortForwardSessionsView sessionView;
    private final Timer timer;
    private boolean closed;

    K8sPortForwardDialog(Component parent, PortForwardConfig.Credentials credentials,
                        String initialNs, String initialKind, String initialName) {
        dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), I18n.get("k8s.forward.title"), Dialog.ModalityType.MODELESS);
        sessionView = new PortForwardSessionsView(this::stopSelected, this::copySelected);
        JTextField executable = Fields.text("kubectl");
        JTextField namespace = Fields.text(initialNs);
        JComboBox<String> kind = Fields.combo(new String[]{"pod", "service", "deployment"}, 125);
        kind.setSelectedItem(initialKind);
        JTextField name = Fields.text(initialName, I18n.get("ui.forward.nameHint"));
        JSpinner local = Fields.spinner(0, 0, 65535, 1);
        local.setEditor(new JSpinner.NumberEditor(local, "#"));
        JTextField remote = Fields.text("8080");
        JButton browse = Buttons.snug(I18n.get("ui.forward.browse"));
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) executable.setText(chooser.getSelectedFile().getAbsolutePath());
        });
        FormGrid form = new FormGrid();
        form.row(I18n.get("k8s.forward.executable"), executable, browse);
        form.row("Namespace", namespace);
        JPanel target = Layouts.box(Tokens.SPACE_SM, 0); target.add(kind, BorderLayout.WEST); target.add(name);
        form.row(I18n.get("k8s.forward.resource"), target);
        JPanel ports = Layouts.columns(Tokens.SPACE_MD,
                Layouts.stack(Tokens.SPACE_XS, Fields.caption(I18n.get("k8s.forward.local")), local),
                Layouts.stack(Tokens.SPACE_XS, Fields.caption(I18n.get("k8s.forward.remote")), remote));
        form.fullRow(ports);
        Card config = Card.titled(I18n.get("ui.forward.newSession")); config.setContent(form);
        JButton start = Buttons.primary(I18n.get("k8s.forward.start")); config.addHeaderAction(start);
        JLabel hint = Fields.note(I18n.get("k8s.forward.hint")); config.setFooter(hint);
        start.addActionListener(e -> {
            try {
                local.commitEdit();
                var selected = new KubectlPortForward.Target(namespace.getText().trim(), (String) kind.getSelectedItem(),
                        name.getText().trim(), (Integer) local.getValue(), remote.getText().trim());
                var session = new KubectlPortForward(executable.getText().trim(), selected);
                session.start(credentials); sessions.add(session);
                refresh(); sessionView.select(sessions.size()-1);
            } catch (Exception error) { UIUtils.error(dialog, Errors.describeRoot(error)); }
        });
        JPanel root = Layouts.page();
        JScrollPane configScroll = Fields.scrollVertical(config); configScroll.setMinimumSize(new Dimension(0, 160));
        sessionView.setMinimumSize(new Dimension(0, 270));
        root.add(Layouts.splitVertical(configScroll, sessionView, 0.25, 0.47));
        dialog.setContentPane(root); dialog.setMinimumSize(new Dimension(700, 570)); dialog.setSize(850, 740);
        dialog.setLocationRelativeTo(parent); dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() { @Override public void windowClosed(WindowEvent e) { close(); } });
        dialog.getRootPane().setDefaultButton(start);
        timer = new Timer(300, e -> refresh()); timer.start();
    }

    void show() { dialog.setVisible(true); }
    boolean isClosed() { return closed; }
    private void stopSelected() {
        int selected = sessionView.selectedIndex();
        if (selected >= 0 && selected < sessions.size()) sessions.get(selected).close();
        refresh();
    }
    private void copySelected() {
        int selected = sessionView.selectedIndex();
        if (selected >= 0 && selected < sessions.size()) {
            var snapshot = sessions.get(selected).snapshot();
            if (snapshot.state() == KubectlPortForward.State.RUNNING) UIUtils.copyToClipboard("127.0.0.1:" + snapshot.localPort());
        }
    }
    private void refresh() {
        if (closed) return;
        java.util.List<PortForwardSessionsView.Row> rows = new ArrayList<>();
        for (var session : sessions) {
            var snapshot = session.snapshot(); var target = session.target();
            rows.add(new PortForwardSessionsView.Row(target.namespace()+" / "+target.kind()+"/"+target.name(),
                    snapshot.localPort() > 0 ? "127.0.0.1:" + snapshot.localPort() : "—",
                    target.remotePort(), snapshot.state(), snapshot.log()));
        }
        sessionView.update(rows);
    }
    @Override public void close() {
        if (closed) return;
        closed = true; timer.stop(); sessions.forEach(KubectlPortForward::close); sessions.clear(); dialog.dispose();
    }
}
