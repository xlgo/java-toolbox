package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.HttpWorkspace.Document;
import com.aqishi.toolbox.infra.secrets.SecretStore;
import com.aqishi.toolbox.util.I18n;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

/** The entire collection, including history and literal header/body credentials, is encrypted in the vault. */
public final class HttpWorkspaceStore {
    private static final String NAMESPACE = "http-workspace";
    private final SecretStore secrets;
    private final ObjectMapper mapper = new ObjectMapper();
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    public HttpWorkspaceStore(SecretStore secrets) { this.secrets = secrets; }
    public SecretStore secrets() { return secrets; }
    public Document load() {
        requireUnlocked();
        String json = secrets.get(NAMESPACE, "workspace");
        if (json == null) return Document.empty();
        try { return java.util.Objects.requireNonNull(mapper.readValue(json, Document.class)); }
        catch (Exception invalid) { throw new IllegalStateException(I18n.get("http.workspace.unreadable")); }
    }
    public synchronized CompletableFuture<Void> edit(UnaryOperator<Document> edit) {
        tail = tail.handle((ok, error) -> null).thenCompose(ignored -> {
            try {
                Document next = edit.apply(load());
                String json = mapper.writeValueAsString(next);
                if (json.length() > 2_000_000) throw new IllegalArgumentException(I18n.get("http.workspace.limit"));
                return secrets.put(NAMESPACE, "workspace", json);
            } catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
        });
        return tail;
    }
    private void requireUnlocked() {
        if (secrets.status() != SecretStore.Status.UNLOCKED) throw new IllegalStateException(I18n.get("http.workspace.unlockRequired"));
    }
}
