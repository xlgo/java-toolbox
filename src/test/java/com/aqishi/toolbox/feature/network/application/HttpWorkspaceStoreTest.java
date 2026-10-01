package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.HttpWorkspace.*;
import com.aqishi.toolbox.infra.secrets.VaultSecretStore;
import com.aqishi.toolbox.vault.VaultUiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpWorkspaceStoreTest {
    @TempDir Path temp;
    @Test void encryptedCollectionSurvivesLockAndUnlockWithoutPlaintextFiles() throws Exception {
        try(var vault=new VaultUiTestSupport(temp)){
            vault.service().create("master".toCharArray()).get();
            var secrets=new VaultSecretStore(vault.service());var store=new HttpWorkspaceStore(secrets);
            var request=new Request("secret request","POST","https://example.test","Authorization: Bearer very-secret-token","private body",true);
            store.edit(d->d.save(request).remember(request).environment(new Environment("test",Map.of("token",new Variable("very-secret-token",true))))).get();
            assertEquals(request,store.load().requests().get(0));
            try(var paths=Files.walk(temp)){
                for(Path path:paths.filter(Files::isRegularFile).filter(p -> !p.equals(vault.paths().getLockFile())).toList()) assertFalse(new String(Files.readAllBytes(path),java.nio.charset.StandardCharsets.UTF_8).contains("very-secret-token"),path.toString());
            }
            vault.service().lock();assertThrows(IllegalStateException.class,store::load);
            assertThrows(java.util.concurrent.ExecutionException.class,()->store.edit(d->d.deleteRequest(request.name())).get());
            secrets.unlock("master".toCharArray()).get();assertEquals(request,store.load().requests().get(0));assertEquals(1,store.load().history().size());
        }
    }
    @Test void corruptOrFutureWorkspaceCannotBeOverwrittenByAnEdit() throws Exception {
        try(var vault=new VaultUiTestSupport(temp)){
            vault.service().create("master".toCharArray()).get();var secrets=new VaultSecretStore(vault.service());var store=new HttpWorkspaceStore(secrets);
            for(String raw:List.of("{broken", "{\"version\":99,\"requests\":[],\"history\":[],\"environments\":[]}")){
                secrets.put("http-workspace","workspace",raw).get();
                assertThrows(IllegalStateException.class,store::load);
                assertThrows(java.util.concurrent.ExecutionException.class,()->store.edit(d->Document.empty()).get());
                assertEquals(raw,secrets.get("http-workspace","workspace"));
            }
        }
    }
}
