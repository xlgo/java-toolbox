package com.aqishi.toolbox.feature.monitor.ui;

import com.aqishi.toolbox.feature.monitor.domain.HostConsent;
import com.aqishi.toolbox.feature.monitor.domain.RemotePermissions;
import com.aqishi.toolbox.feature.monitor.domain.RemotePermissions.Permission;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;

/**
 * Modal host-side consent prompt for an incoming remote-desktop session.
 *
 * <p>Shows who is asking (name and ID as claimed by signaling, the transport
 * address), the SAS the host user must compare with the controller, and three
 * choices. Deny is the default button and the result of closing the window,
 * pressing Escape or letting the countdown run out. File transfer and terminal
 * are separate opt-in checkboxes, off by default.</p>
 */
public final class RemoteConsentDialog implements HostConsent.Strategy {

    private final Component parent;
    private final int timeoutSeconds;

    public RemoteConsentDialog(Component parent, int timeoutSeconds) {
        this.parent = parent;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public CompletableFuture<RemotePermissions> requestConsent(HostConsent.Request request) {
        CompletableFuture<RemotePermissions> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> show(request, result));
        return result;
    }

    private void show(HostConsent.Request request, CompletableFuture<RemotePermissions> result) {
        if (result.isDone()) return;
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, I18n.get("tool.remotedesktop.consent_title"),
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.setAlwaysOnTop(true);

        JCheckBox files = new JCheckBox(I18n.get("tool.remotedesktop.consent_allow_files"));
        JCheckBox terminal = new JCheckBox(I18n.get("tool.remotedesktop.consent_allow_terminal"));
        JButton viewOnly = new JButton(I18n.get("tool.remotedesktop.consent_allow_view"));
        JButton control = new JButton(I18n.get("tool.remotedesktop.consent_allow_control"));
        JButton deny = new JButton(I18n.get("tool.remotedesktop.consent_deny"));
        JLabel countdown = new JLabel(I18n.get("tool.remotedesktop.consent_countdown", timeoutSeconds));

        viewOnly.addActionListener(e -> result.complete(grant(false, files, terminal)));
        control.addActionListener(e -> result.complete(grant(true, files, terminal)));
        deny.addActionListener(e -> result.complete(RemotePermissions.none()));
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                result.complete(RemotePermissions.none());
            }
        });
        dialog.getRootPane().registerKeyboardAction(e -> result.complete(RemotePermissions.none()),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        int[] remaining = {timeoutSeconds};
        Timer timer = new Timer(1000, e -> {
            remaining[0]--;
            countdown.setText(I18n.get("tool.remotedesktop.consent_countdown", Math.max(0, remaining[0])));
            if (remaining[0] <= 0) result.complete(RemotePermissions.none());
        });
        result.whenComplete((value, error) -> SwingUtilities.invokeLater(() -> {
            timer.stop();
            dialog.dispose();
        }));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(viewOnly);
        buttons.add(control);
        buttons.add(deny);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
        content.add(wrapped(I18n.get("tool.remotedesktop.consent_message")), BorderLayout.NORTH);
        content.add(details(request, files, terminal), BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(countdown, BorderLayout.WEST);
        south.add(buttons, BorderLayout.EAST);
        content.add(south, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(deny);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(Math.max(460, dialog.getWidth()), dialog.getHeight()));
        dialog.setLocationRelativeTo(owner);
        timer.start();
        if (result.isDone()) {
            timer.stop();
            dialog.dispose();
            return;
        }
        deny.requestFocusInWindow();
        dialog.setVisible(true);
    }

    private static RemotePermissions grant(boolean control, JCheckBox files, JCheckBox terminal) {
        EnumSet<Permission> granted = EnumSet.of(Permission.VIEW);
        if (control) granted.add(Permission.CONTROL);
        if (files.isSelected()) granted.add(Permission.FILES);
        if (terminal.isSelected()) granted.add(Permission.TERMINAL);
        return RemotePermissions.of(granted);
    }

    private static JComponent details(HostConsent.Request request, JCheckBox files, JCheckBox terminal) {
        JPanel grid = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 0, 3, 12);
        int row = 0;
        row = addRow(grid, c, row, I18n.get("tool.remotedesktop.consent_controller"),
                new JLabel(request.controllerName() + "  (" + request.controllerId() + ")"));
        JLabel note = new JLabel(I18n.get("tool.remotedesktop.consent_unverified"));
        note.setFont(note.getFont().deriveFont(Font.ITALIC));
        note.setForeground(UIManager.getColor("Label.disabledForeground"));
        row = addRow(grid, c, row, "", note);
        row = addRow(grid, c, row, I18n.get("tool.remotedesktop.consent_address"),
                new JLabel(request.remoteAddress()));
        row = addRow(grid, c, row, I18n.get("tool.remotedesktop.consent_transport"),
                new JLabel(request.transport()));
        row = addRow(grid, c, row, I18n.get("tool.remotedesktop.consent_password"),
                new JLabel(I18n.get(request.passwordVerified()
                        ? "tool.remotedesktop.consent_password_verified"
                        : "tool.remotedesktop.consent_password_none")));
        JLabel sas = new JLabel(request.sas());
        sas.setFont(sas.getFont().deriveFont(Font.BOLD, sas.getFont().getSize2D() * 2.2f));
        row = addRow(grid, c, row, I18n.get("tool.remotedesktop.consent_sas"), sas);
        row = addRow(grid, c, row, "", files);
        addRow(grid, c, row, "", terminal);
        return grid;
    }

    private static int addRow(JPanel grid, GridBagConstraints c, int row, String label, JComponent value) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        grid.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        grid.add(value, c);
        return row + 1;
    }

    private static JComponent wrapped(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setColumns(38);
        area.setFont(UIManager.getFont("Label.font"));
        area.setBorder(null);
        return area;
    }
}
