package com.aqishi.toolbox.ui.secrets;

import com.aqishi.toolbox.infra.secrets.ProfileSecretManager;
import com.aqishi.toolbox.infra.secrets.ProfileSecretManager.Resolution;
import com.aqishi.toolbox.infra.secrets.ProfileSecretManager.SaveOutcome;
import com.aqishi.toolbox.infra.secrets.SecretBearing;
import com.aqishi.toolbox.infra.secrets.SecretPrompter;
import com.aqishi.toolbox.infra.secrets.SecretStore;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Swing glue between a connection panel and its {@link ProfileSecretManager}: the notice
 * bar, filling secret fields from the vault, clearing them when the vault locks, and the
 * save / connect flows with their error messages.
 */
public final class ProfileSecretUi<P extends SecretBearing> implements ProfileSecretManager.Listener {
    private final ProfileSecretManager<P> manager;
    private final SecretPrompter prompter;
    private final Supplier<Component> parent;
    private final SecretNoticeBar notice;
    private final List<Binding<P>> bindings = new ArrayList<>();

    public ProfileSecretUi(ProfileSecretManager<P> manager, Supplier<Component> parent) {
        this(manager, new SwingSecretPrompter(parent), parent);
    }

    public ProfileSecretUi(ProfileSecretManager<P> manager, SecretPrompter prompter,
                           Supplier<Component> parent) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.prompter = Objects.requireNonNull(prompter, "prompter");
        this.parent = Objects.requireNonNull(parent, "parent");
        this.notice = new SecretNoticeBar(this::unlockFromNotice);
        manager.addListener(this);
    }

    public ProfileSecretManager<P> manager() {
        return manager;
    }

    public SecretNoticeBar notice() {
        return notice;
    }

    /** Puts the notice bar above {@code content}. */
    public JComponent wrap(JComponent content) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(notice, BorderLayout.NORTH);
        wrapper.add(content, BorderLayout.CENTER);
        refreshNotice();
        return wrapper;
    }

    /**
     * Keeps {@code field} in step with the vault: filled on unlock when empty, cleared on
     * lock when it still shows the vault value (a value the user typed is left alone).
     */
    public void bind(JTextComponent field, Supplier<P> selected, String secretField) {
        bindings.add(new Binding<>(field, selected, secretField));
    }

    /** Shows the known secret of {@code profile} in {@code field} (empty when locked). */
    public void fill(JTextComponent field, P profile, String secretField) {
        String value = profile == null ? null : manager.knownSecrets(profile).get(secretField);
        field.setText(value == null ? "" : value);
        for (Binding<P> binding : bindings) {
            if (binding.field == field) binding.filled = value;
        }
    }

    /** Saves through the manager and reports failures; {@code after} runs on the EDT. */
    public void save(String name, P profile, Consumer<SaveOutcome> after) {
        java.util.concurrent.CompletableFuture<SaveOutcome> pending;
        try {
            pending = manager.save(name, profile, prompter);
        } catch (RuntimeException preferencesFailed) {
            // Same handling as before the vault: the preference write failure is logged.
            com.aqishi.toolbox.util.Errors.log("Unable to save connection profile", preferencesFailed);
            return;
        }
        pending.whenComplete((outcome, error) -> onEdt(() -> {
            if (error != null) {
                com.aqishi.toolbox.util.Errors.log("Unable to save connection profile", error);
                return;
            }
            if (outcome == SaveOutcome.UNLOCK_FAILED) {
                UIUtils.error(parent.get(), I18n.get("secrets.unlock.failed"));
                return;
            }
            if (outcome == SaveOutcome.SECRET_WRITE_FAILED) {
                UIUtils.error(parent.get(), I18n.get("secrets.save.failed"));
            }
            if (outcome == SaveOutcome.SAVED_WITH_SECRET) {
                // The typed value is now the vault copy: drop it from the form on lock too.
                for (Binding<P> binding : bindings) {
                    String shown = binding.field.getText();
                    binding.filled = shown.isEmpty() ? null : shown;
                }
            }
            if (outcome != SaveOutcome.CANCELLED && after != null) after.accept(outcome);
        }));
    }

    /**
     * Runs {@code proceed} with the secrets of {@code profile}, asking to unlock (or for a
     * session password) first when needed. Nothing runs when the user cancels.
     */
    public void withSecrets(P profile, boolean sessionEntryAllowed, Consumer<Resolution> proceed) {
        manager.resolve(profile, prompter, sessionEntryAllowed).whenComplete((resolution, error) -> onEdt(() -> {
            if (error != null || resolution.kind() == Resolution.Kind.UNLOCK_FAILED) {
                UIUtils.error(parent.get(), I18n.get("secrets.unlock.failed"));
                return;
            }
            if (resolution.cancelled()) return;
            if (resolution.kind() == Resolution.Kind.MISSING) {
                UIUtils.error(parent.get(), I18n.get("secrets.connect.missing", profile.secretLabel()));
                return;
            }
            proceed.accept(resolution);
        }));
    }

    /**
     * Connect helper: when {@code field} is empty but the selected profile has a stored
     * secret, resolves it into the field first; then runs {@code action}.
     */
    public void ensureField(JTextComponent field, P selected, String secretField, Runnable action) {
        if (!field.getText().isEmpty() || selected == null
                || !(selected.secretStored() || selected.plaintextPending())) {
            action.run();
            return;
        }
        withSecrets(selected, true, resolution -> {
            String value = resolution.field(secretField);
            if (value != null) {
                field.setText(value);
                for (Binding<P> binding : bindings) {
                    if (binding.field == field) binding.filled = value;
                }
            }
            action.run();
        });
    }

    private void unlockFromNotice() {
        char[] master = new SwingSecretPrompter(parent).askMasterPassword();
        if (master == null) return;
        manager.store().unlock(master).whenComplete((ignored, error) -> {
            if (error != null) onEdt(() -> UIUtils.error(parent.get(), I18n.get("secrets.unlock.failed")));
        });
    }

    public void refreshNotice() {
        notice.update(manager.plaintextCount(), manager.vaultStoredCount(),
                manager.status(), manager.migrating());
    }

    @Override
    public void onSecretsChanged() {
        refreshNotice();
    }

    @Override
    public void onSecretsLocked() {
        for (Binding<P> binding : bindings) {
            String filled = binding.filled;
            binding.filled = null;
            if (filled != null && filled.equals(binding.field.getText())) {
                binding.field.setText("");
            }
        }
    }

    @Override
    public void onSecretsUnlocked() {
        for (Binding<P> binding : bindings) {
            P selected = binding.selected.get();
            if (selected != null && binding.field.getText().isEmpty() && binding.field.isEnabled()) {
                fill(binding.field, selected, binding.secretField);
            }
        }
    }

    public void dispose() {
        manager.removeListener(this);
    }

    private static void onEdt(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) task.run();
        else SwingUtilities.invokeLater(task);
    }

    private static final class Binding<P> {
        final JTextComponent field;
        final Supplier<P> selected;
        final String secretField;
        String filled;

        Binding(JTextComponent field, Supplier<P> selected, String secretField) {
            this.field = field;
            this.selected = selected;
            this.secretField = secretField;
        }
    }

    /** Status shortcut for panels deciding whether to show vault affordances. */
    public boolean vaultEnabled() {
        return manager.status() != SecretStore.Status.DISABLED;
    }
}
