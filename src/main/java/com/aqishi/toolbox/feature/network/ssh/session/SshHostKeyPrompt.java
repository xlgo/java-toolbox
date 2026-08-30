package com.aqishi.toolbox.feature.network.ssh.session;

/**
 * Lets the SSH session ask the user about an unknown host key without
 * importing Swing into the transport layer.
 *
 * <p>JSch calls back into {@code UserInfo} from the connecting thread, which
 * used to mean a modal dialog was raised from the middle of a session object.
 * That made the session impossible to unit test and froze whatever thread
 * happened to be connecting. The UI layer now supplies an implementation.</p>
 */
public interface SshHostKeyPrompt {

    /**
     * Asks the user to accept an unknown or changed host key.
     *
     * @param message the prompt text produced by JSch
     * @return true to trust the host and continue connecting
     */
    boolean confirmHostKey(String message);

    /** Shows a non-blocking informational message from the SSH layer. */
    void showMessage(String message);

    /**
     * Denies every prompt. Used for headless runs and tests, where there is no
     * one to answer and silently trusting a host would be worse than failing.
     */
    static SshHostKeyPrompt denyAll() {
        return new SshHostKeyPrompt() {
            @Override
            public boolean confirmHostKey(String message) {
                return false;
            }

            @Override
            public void showMessage(String message) {
                // Nothing to show without a UI.
            }
        };
    }
}
