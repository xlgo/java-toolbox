package com.aqishi.toolbox.infra.secrets;

import java.util.Map;

/**
 * A persisted connection profile whose secret fields live in the vault.
 *
 * <p>Method names deliberately avoid the {@code get/set/is} prefixes so Jackson never
 * picks them up as JSON properties; implementations keep their own JSON layout.</p>
 *
 * <ul>
 *   <li>{@link #secretId()} is a stable UUID persisted with the profile, so a profile
 *       keyed by its display name keeps its vault entry.</li>
 *   <li>{@link #secretStored()} is the only secret-related value written to preferences.</li>
 *   <li>{@link #plaintextPending()} is runtime-only: the profile was loaded with a legacy
 *       plaintext secret that has not yet been moved into the vault.</li>
 * </ul>
 */
public interface SecretBearing {

    String secretId();

    void assignSecretId(String id);

    /** Current in-memory secret values by field name (empty values may be omitted). */
    Map<String, String> secretFields();

    /** Replaces the in-memory secret values; {@code null} or empty clears them. */
    void applySecretFields(Map<String, String> fields);

    /** The field a password typed "for this session only" belongs to. */
    String primarySecretField();

    boolean secretStored();

    void markSecretStored(boolean stored);

    boolean plaintextPending();

    void markPlaintextPending(boolean pending);

    /** Display name used in prompts; never a secret. */
    String secretLabel();
}
