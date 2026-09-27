package com.aqishi.toolbox.ui.secrets;

import com.aqishi.toolbox.infra.secrets.SecretPrompter;
import com.aqishi.toolbox.infra.secrets.SecretStore;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;

/** Modal-dialog {@link SecretPrompter}; every typed array is handed over or wiped. */
public final class SwingSecretPrompter implements SecretPrompter {
    private static final int TEXT_WIDTH = 360;

    private final Supplier<Component> parent;

    public SwingSecretPrompter(Supplier<Component> parent) {
        this.parent = Objects.requireNonNull(parent, "parent");
    }

    @Override
    public Answer askBeforeSave(String profileLabel, SecretStore.Status status) {
        if (status == SecretStore.Status.LOCKED) {
            JPasswordField master = Fields.password();
            FormGrid form = new FormGrid();
            form.row(I18n.get("vault.masterPassword"), master);
            int choice = show(I18n.get("secrets.save.title"), I18n.get("secrets.save.locked"), form,
                    I18n.get("vault.unlock"), I18n.get("secrets.save.withoutSecret"), I18n.get("vault.cancel"));
            if (choice == 0) return unlockOrCancel(master);
            master.setText("");
            return choice == 1 ? Answer.skipSecret() : Answer.cancel();
        }
        String message = status == SecretStore.Status.NO_VAULT
                ? I18n.get("secrets.save.noVault") : I18n.get("secrets.save.unavailable");
        int choice = show(I18n.get("secrets.save.title"), message, null,
                I18n.get("secrets.save.withoutSecret"), I18n.get("vault.cancel"));
        return choice == 0 ? Answer.skipSecret() : Answer.cancel();
    }

    @Override
    public Answer askBeforeConnect(String profileLabel, SecretStore.Status status,
                                   boolean sessionEntryAllowed) {
        String label = profileLabel == null ? "" : profileLabel;
        boolean locked = status == SecretStore.Status.LOCKED;
        JPasswordField master = Fields.password();
        JPasswordField session = Fields.password();
        FormGrid form = new FormGrid();
        if (locked) form.row(I18n.get("vault.masterPassword"), master);
        if (sessionEntryAllowed) form.row(I18n.get("secrets.connect.sessionPassword"), session);
        String message = locked
                ? I18n.get(sessionEntryAllowed ? "secrets.connect.locked" : "secrets.connect.lockedUnlockOnly", label)
                : I18n.get("secrets.connect.unavailable", label);
        String alternative = sessionEntryAllowed
                ? I18n.get("secrets.connect.useSession") : I18n.get("secrets.connect.withoutSecret");
        try {
            if (locked) {
                int choice = show(I18n.get("secrets.connect.title"), message, form,
                        I18n.get("vault.unlock"), alternative, I18n.get("vault.cancel"));
                if (choice == 0) return unlockOrCancel(master);
                if (choice == 1) return sessionEntryAllowed ? sessionAnswer(session) : Answer.skipSecret();
                return Answer.cancel();
            }
            int choice = show(I18n.get("secrets.connect.title"), message,
                    sessionEntryAllowed ? form : null, alternative, I18n.get("vault.cancel"));
            if (choice != 0) return Answer.cancel();
            return sessionEntryAllowed ? sessionAnswer(session) : Answer.skipSecret();
        } finally {
            master.setText("");
            session.setText("");
        }
    }

    /**
     * Wraps {@code delegate} so it may be called from a background thread (an SSH connect,
     * a tunnel bridge): each question is asked on the EDT and the caller waits for it.
     */
    public static SecretPrompter onEdt(SecretPrompter delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new SecretPrompter() {
            @Override
            public Answer askBeforeSave(String profileLabel, SecretStore.Status status) {
                return ask(() -> delegate.askBeforeSave(profileLabel, status));
            }

            @Override
            public Answer askBeforeConnect(String profileLabel, SecretStore.Status status,
                                           boolean sessionEntryAllowed) {
                return ask(() -> delegate.askBeforeConnect(profileLabel, status, sessionEntryAllowed));
            }
        };
    }

    private static Answer ask(Supplier<Answer> question) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) return question.get();
        java.util.concurrent.atomic.AtomicReference<Answer> answer =
                new java.util.concurrent.atomic.AtomicReference<>(Answer.cancel());
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> answer.set(question.get()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException failed) {
            return Answer.cancel();
        }
        return answer.get();
    }

    /** Asks for the master password alone; returns null when cancelled or empty. */
    public char[] askMasterPassword() {
        JPasswordField master = Fields.password();
        FormGrid form = new FormGrid();
        form.row(I18n.get("vault.masterPassword"), master);
        int choice = show(I18n.get("vault.unlock.title"), null, form,
                I18n.get("vault.unlock"), I18n.get("vault.cancel"));
        char[] value = master.getPassword();
        master.setText("");
        if (choice != 0 || value.length == 0) {
            Arrays.fill(value, '\0');
            return null;
        }
        return value;
    }

    private static Answer unlockOrCancel(JPasswordField master) {
        char[] value = master.getPassword();
        master.setText("");
        if (value.length == 0) return Answer.cancel();
        return Answer.unlock(value);
    }

    private static Answer sessionAnswer(JPasswordField session) {
        char[] value = session.getPassword();
        session.setText("");
        if (value.length == 0) return Answer.skipSecret();
        return Answer.session(value);
    }

    private int show(String title, String message, JComponent fields, String... options) {
        JPanel body = Layouts.box(0, Tokens.SPACE_MD);
        if (message != null) {
            JLabel text = new JLabel("<html><div style='width:" + TEXT_WIDTH + "px'>"
                    + escape(message) + "</div></html>");
            text.setFont(Tokens.fontBody());
            body.add(text, BorderLayout.NORTH);
        }
        if (fields != null) body.add(fields, BorderLayout.CENTER);
        return JOptionPane.showOptionDialog(parent.get(), body, title,
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
