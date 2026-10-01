package com.aqishi.toolbox.feature.cloud.application;

import com.aqishi.toolbox.util.I18n;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Private, session-scoped kubeconfig. Credentials never appear in process arguments or normal settings. */
public final class PortForwardConfig implements AutoCloseable {
    public record Credentials(String server, String token, boolean skipTls, String ca, String certificate, String key) {
        public Credentials {
            var uri=URI.create(server);
            if (!Set.of("https", "http").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null)
                throw new IllegalArgumentException(I18n.get("k8s.forward.serverInvalid"));
            token=Objects.requireNonNullElse(token, ""); ca=Objects.requireNonNullElse(ca, "");
            certificate=Objects.requireNonNullElse(certificate, ""); key=Objects.requireNonNullElse(key, "");
            if(certificate.isBlank()!=key.isBlank()) throw new IllegalArgumentException(I18n.get("k8s.forward.keyPair"));
        }
        @Override public String toString() { return "Kubernetes credentials [redacted]"; }
    }
    private final Path directory;
    private final Path file;
    private PortForwardConfig(Path directory) { this.directory=directory; this.file=directory.resolve("config.json"); }
    public Path path() { return file; }
    public static PortForwardConfig create(Credentials credentials) throws Exception {
        Path dir;
        if(FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            dir=Files.createTempDirectory("toolbox-portforward-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else dir=Files.createTempDirectory("toolbox-portforward-");
        PortForwardConfig config=new PortForwardConfig(dir);
        try {
            if(!Files.getFileStore(dir).supportsFileAttributeView("posix")) {
                AclFileAttributeView acl=Files.getFileAttributeView(dir,AclFileAttributeView.class);
                if(acl==null) throw new java.io.IOException(I18n.get("k8s.forward.privateFile"));
                AclEntry owner=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(dir))
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .setFlags(AclEntryFlag.FILE_INHERIT,AclEntryFlag.DIRECTORY_INHERIT).build();
                acl.setAcl(List.of(owner));
            }
            Files.writeString(config.file,json(credentials),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            if(Files.getFileStore(dir).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(config.file,PosixFilePermissions.fromString("rw-------"));
            dir.toFile().deleteOnExit(); config.file.toFile().deleteOnExit();
            return config;
        } catch(Exception error) { config.close(); throw error; }
    }
    static String json(Credentials c) throws Exception {
        Map<String,Object> cluster=new LinkedHashMap<>(); cluster.put("server",c.server());
        if(c.skipTls()) cluster.put("insecure-skip-tls-verify",true);
        else if(!c.ca().isBlank()) cluster.put("certificate-authority-data",base64(c.ca()));
        Map<String,Object> user=new LinkedHashMap<>();
        if(!c.token().isBlank()) user.put("token",c.token());
        if(!c.certificate().isBlank()) { user.put("client-certificate-data",base64(c.certificate())); user.put("client-key-data",base64(c.key())); }
        return new ObjectMapper().writeValueAsString(Map.of("apiVersion","v1","kind","Config",
                "clusters",List.of(Map.of("name","toolbox","cluster",cluster)),
                "users",List.of(Map.of("name","toolbox","user",user)),
                "contexts",List.of(Map.of("name","toolbox","context",Map.of("cluster","toolbox","user","toolbox"))),
                "current-context","toolbox"));
    }
    private static String base64(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    @Override public void close() throws java.io.IOException { Files.deleteIfExists(file); Files.deleteIfExists(directory); }
}
