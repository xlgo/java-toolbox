package com.aqishi.toolbox.infra.secrets;

import java.util.Arrays;

/**
 * Asks the user what to do when a secret is needed but the vault is not open.
 *
 * <p>Called on the UI thread. The Swing implementation shows modal dialogs; tests
 * inject scripted answers so no dialog is ever opened.</p>
 */
public interface SecretPrompter {

    /**
     * Saving a profile with a password while the vault cannot take it.
     * Valid answers: {@link Answer#unlock}, {@link Answer#skipSecret()}, {@link Answer#cancel()}.
     */
    Answer askBeforeSave(String profileLabel, SecretStore.Status status);

    /**
     * Connecting with a stored secret while the vault is not unlocked.
     * Valid answers: {@link Answer#unlock}, {@link Answer#session} (only when
     * {@code sessionEntryAllowed}), {@link Answer#skipSecret()}, {@link Answer#cancel()}.
     */
    Answer askBeforeConnect(String profileLabel, SecretStore.Status status,
                            boolean sessionEntryAllowed);

    /** The user's decision; any carried characters are owned and wiped by the receiver. */
    final class Answer {
        public enum Kind { UNLOCK, SESSION_SECRET, SKIP_SECRET, CANCEL }

        private final Kind kind;
        private final char[] value;

        private Answer(Kind kind, char[] value) {
            this.kind = kind;
            this.value = value;
        }

        public static Answer unlock(char[] masterPassword) {
            return new Answer(Kind.UNLOCK, masterPassword == null ? new char[0] : masterPassword);
        }

        public static Answer session(char[] secret) {
            return new Answer(Kind.SESSION_SECRET, secret == null ? new char[0] : secret);
        }

        public static Answer skipSecret() {
            return new Answer(Kind.SKIP_SECRET, null);
        }

        public static Answer cancel() {
            return new Answer(Kind.CANCEL, null);
        }

        public Kind kind() {
            return kind;
        }

        /** The carried characters (master password or session secret); may be null. */
        public char[] value() {
            return value;
        }

        public void wipe() {
            if (value != null) Arrays.fill(value, '\0');
        }

        @Override
        public String toString() {
            return "Answer[" + kind + "]";
        }
    }

    /** Never prompts: saves without secrets and connects without them. */
    static SecretPrompter nonInteractive() {
        return new SecretPrompter() {
            @Override
            public Answer askBeforeSave(String profileLabel, SecretStore.Status status) {
                return Answer.skipSecret();
            }

            @Override
            public Answer askBeforeConnect(String profileLabel, SecretStore.Status status,
                                           boolean sessionEntryAllowed) {
                return Answer.skipSecret();
            }
        };
    }
}
