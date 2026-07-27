package com.aqishi.toolbox.vault;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

final class VaultTestSupport implements AutoCloseable {
    private final ApplicationPaths paths;
    private final VaultFileLock fileLock;
    private final VaultRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

    VaultTestSupport(Path temporaryDirectory) throws IOException {
        this(temporaryDirectory, new AtomicFiles());
    }

    VaultTestSupport(Path temporaryDirectory, AtomicFiles atomicFiles) throws IOException {
        Path absTemp = temporaryDirectory.toAbsolutePath();
        Map<String, String> environment = new HashMap<>();
        environment.put("APPDATA", absTemp.resolve("data").toString());
        environment.put("XDG_DATA_HOME", absTemp.resolve("data").toString());
        environment.put("XDG_CONFIG_HOME", absTemp.resolve("config").toString());
        paths = ApplicationPaths.resolve(
                System.getProperty("os.name"),
                absTemp.toString(),
                environment,
                absTemp.resolve("legacy"));
        paths.createPrivateDirectories();
        fileLock = new VaultFileLock(paths.getLockFile());
        repository = new VaultRepository(paths, atomicFiles, new VaultCrypto(), fileLock);
    }

    ApplicationPaths paths() {
        return paths;
    }

    VaultRepository repository() {
        return repository;
    }

    VaultEnvelope readEnvelope() throws IOException {
        return mapper.readValue(paths.getVaultFile().toFile(), VaultEnvelope.class);
    }

    VaultData sampleData() {
        VaultData data = new VaultData();
        data.setPasswordAccounts(Collections.singletonList(
                new PasswordAccount("GitHub", "dev", "password", "https://github.com")));
        data.setTotpAccounts(Collections.singletonList(
                new TotpAccount("totp-1", "Mail", "JBSWY3DPEHPK3PXP",
                        "Example", "SHA256", 8, 45, false)));
        return data;
    }

    @Override
    public void close() {
        repository.close();
        fileLock.close();
    }
}
