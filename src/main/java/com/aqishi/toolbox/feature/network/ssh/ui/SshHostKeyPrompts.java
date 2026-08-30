package com.aqishi.toolbox.feature.network.ssh.ui;

import com.aqishi.toolbox.feature.network.ssh.domain.SshHostKeyPrompt;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.util.concurrent.atomic.AtomicBoolean;
import com.aqishi.toolbox.feature.network.ssh.domain.SshHostKeyPrompt;

/**
 * Swing implementation of the SSH host-key prompt.
 *
 * <p>JSch asks about an unknown host key from the connecting thread, not the
 * event thread, so the question is marshalled back onto the EDT and the caller
 * blocks until the user answers. The session cannot proceed without an answer,
 * so waiting here is expected.</p>
 */
public final class SshHostKeyPrompts {

    private SshHostKeyPrompts() {
    }

    public static SshHostKeyPrompt dialogs(Component parent) {
        return new DialogPrompt(parent);
    }

    private static final class DialogPrompt implements SshHostKeyPrompt {
        private final Component parent;

        DialogPrompt(Component parent) {
            this.parent = parent;
        }

        @Override
        public boolean confirmHostKey(String message) {
            AtomicBoolean accepted = new AtomicBoolean(false);
            onEventThread(() -> accepted.set(UIUtils.confirm(parent, format(message), "确认 SSH 主机指纹")));
            return accepted.get();
        }

        @Override
        public void showMessage(String message) {
            if (message == null || message.trim().isEmpty()) {
                return;
            }
            onEventThread(() -> UIUtils.info(parent, message));
        }

        /**
         * Wraps the JSch prompt so the fingerprint is legible: it embeds the
         * key type and fingerprint in one long line that a plain dialog would
         * render as an unreadable paragraph.
         */
        private static String format(String message) {
            String body = message.replace("\n", "<br>");
            return "<html><body style='width: 420px'>" + body
                    + "<br><br>只有确认该指纹属于目标主机时才选择「是」。</body></html>";
        }

        private static void onEventThread(Runnable action) {
            if (SwingUtilities.isEventDispatchThread()) {
                action.run();
                return;
            }
            try {
                SwingUtilities.invokeAndWait(action);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                // A dialog that cannot be shown must not silently trust the host.
                System.err.println("SSH 主机指纹确认失败: " + error.getMessage());
                UIManager.getLookAndFeel().provideErrorFeedback(null);
            }
        }
    }
}
