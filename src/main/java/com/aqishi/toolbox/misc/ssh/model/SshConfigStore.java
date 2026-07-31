package com.aqishi.toolbox.misc.ssh.model;

import com.aqishi.toolbox.vault.ApplicationPaths;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSH 服务器连接配置持久化存储
 */
public class SshConfigStore {

    private static final String FILE_NAME = "ssh_servers.json";
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static SshConfigStore instance;

    private final List<SshConnectionConfig> configs = new CopyOnWriteArrayList<>();
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    private final File configFile;

    public SshConfigStore() {
        File configDir = ApplicationPaths.systemDefault().getDataDirectory().toFile();
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        this.configFile = new File(configDir, FILE_NAME);
        load();
    }

    public static synchronized SshConfigStore getInstance() {
        if (instance == null) {
            instance = new SshConfigStore();
        }
        return instance;
    }

    public synchronized void load() {
        configs.clear();
        boolean migrationNeeded = false;
        if (configFile.exists() && configFile.isFile()) {
            try {
                List<SshConnectionConfig> list = mapper.readValue(configFile, new TypeReference<List<SshConnectionConfig>>() {});
                if (list != null) {
                    for (SshConnectionConfig config : list) {
                        if (config == null) continue;
                        if (config.getId() == null || config.getId().trim().isEmpty()) {
                            config.setId(UUID.randomUUID().toString());
                        }
                        migrationNeeded |= hasLegacySensitiveValues(config);
                        config.normalizeSensitiveValues();
                        configs.add(config);
                    }
                }
            } catch (Exception e) {
                System.err.println("无法读取 SSH 配置: " + e.getMessage());
            }
        }
        if (migrationNeeded) {
            save();
        }
    }

    private static boolean hasLegacySensitiveValues(SshConnectionConfig config) {
        return isLegacy(config.getEncryptedPassword())
                || isLegacy(config.getEncryptedPassphrase())
                || isLegacy(config.getEncryptedKeyContent());
    }

    private static boolean isLegacy(String value) {
        return value != null && !value.isEmpty() && !SshSecurityUtils.isEncrypted(value);
    }

    public synchronized void save() {
        try {
            File parent = configFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("无法创建 SSH 配置目录: " + parent);
            }
            for (SshConnectionConfig config : configs) {
                config.normalizeSensitiveValues();
            }
            byte[] json = mapper.writeValueAsBytes(configs);
            Path target = configFile.toPath();
            Path temporary = target.resolveSibling(configFile.getName() + ".tmp");
            Files.write(temporary, json, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(target);
            notifyChanged();
        } catch (IOException e) {
            System.err.println("无法保存 SSH 配置: " + e.getMessage());
        }
    }

    public void addChangeListener(Runnable listener) {
        if (listener != null && !changeListeners.contains(listener)) changeListeners.add(listener);
    }

    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    private void notifyChanged() {
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 使用当前用户数据目录的 ACL；POSIX 平台额外收紧到 600。
        }
    }

    public List<SshConnectionConfig> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(configs));
    }

    public Map<String, List<SshConnectionConfig>> getGroupedConfigs() {
        Map<String, List<SshConnectionConfig>> map = new LinkedHashMap<>();
        for (SshConnectionConfig cfg : configs) {
            String grp = cfg.getGroup();
            map.computeIfAbsent(grp, k -> new ArrayList<>()).add(cfg);
        }
        return map;
    }

    public SshConnectionConfig findById(String id) {
        if (id == null) return null;
        for (SshConnectionConfig cfg : configs) {
            if (id.equals(cfg.getId())) {
                return cfg;
            }
        }
        return null;
    }

    public synchronized void addOrUpdate(SshConnectionConfig config) {
        if (config == null) return;
        int idx = -1;
        for (int i = 0; i < configs.size(); i++) {
            if (configs.get(i).getId().equals(config.getId())) {
                idx = i;
                break;
            }
        }
        if (idx >= 0) {
            configs.set(idx, config);
        } else {
            configs.add(config);
        }
        save();
    }

    public synchronized boolean delete(String id) {
        if (id == null) return false;
        boolean removed = configs.removeIf(cfg -> id.equals(cfg.getId()));
        if (removed) {
            save();
        }
        return removed;
    }
}
