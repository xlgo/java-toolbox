package com.aqishi.toolbox.feature.cloud.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerRunConverterTest {

    private static JsonNode convert(String command) throws Exception {
        return new YAMLMapper().readTree(DockerRunConverter.convert(command).yaml());
    }

    private static JsonNode service(JsonNode root, String name) {
        return root.path("services").path(name);
    }

    @Test
    void convertsTheDefaultExample() throws Exception {
        JsonNode root = convert("docker run -d --name nginx-server -p 8080:80 -v /my/data:/usr/share/nginx/html "
                + "-e TZ=Asia/Shanghai --restart always nginx:latest");
        JsonNode web = service(root, "nginx-server");

        assertEquals("nginx:latest", web.path("image").asText());
        assertEquals("nginx-server", web.path("container_name").asText());
        assertEquals("8080:80", web.path("ports").get(0).asText());
        assertEquals("/my/data:/usr/share/nginx/html", web.path("volumes").get(0).asText());
        assertEquals("TZ=Asia/Shanghai", web.path("environment").get(0).asText());
        assertEquals("always", web.path("restart").asText());
    }

    /** 回归：正则切词把 KEY="a b" 拆开，后半段被当成了镜像名。 */
    @Test
    void keepsQuotedValuesWhole() throws Exception {
        JsonNode web = service(convert("docker run -e MSG=\"hello world: yes\" --name web nginx"), "web");

        assertEquals("nginx", web.path("image").asText());
        assertEquals("MSG=hello world: yes", web.path("environment").get(0).asText());
    }

    /** 回归：不认识的带值参数只跳过参数本身，值被当成镜像，真正的镜像变成了 command。 */
    @Test
    void consumesValuesOfOtherOptions() throws Exception {
        JsonNode web = service(convert("docker run -w /app -u 1000 --env-file .env -m 512m --entrypoint sh "
                + "--add-host db:10.0.0.2 --cap-add NET_ADMIN --name web node:20 npm start"), "web");

        assertEquals("node:20", web.path("image").asText());
        assertEquals("/app", web.path("working_dir").asText());
        assertEquals("1000", web.path("user").asText());
        assertEquals(".env", web.path("env_file").get(0).asText());
        assertEquals("512m", web.path("mem_limit").asText());
        assertEquals("sh", web.path("entrypoint").asText());
        assertEquals("db:10.0.0.2", web.path("extra_hosts").get(0).asText());
        assertEquals("NET_ADMIN", web.path("cap_add").get(0).asText());
        assertEquals(List.of("npm", "start"), List.of(web.path("command").get(0).asText(), web.path("command").get(1).asText()));
    }

    /** 回归：command 被写在顶层 networks 之后，缩进上成了 networks.<net> 的子项。 */
    @Test
    void keepsCommandInsideServiceWhenNetworkIsExternal() throws Exception {
        JsonNode root = convert("docker run --network backend --name api myapp:1 serve --port 80");

        JsonNode api = service(root, "api");
        assertEquals("serve", api.path("command").get(0).asText());
        assertEquals("backend", api.path("networks").get(0).asText());
        assertTrue(root.path("networks").path("backend").path("external").asBoolean());
        assertTrue(root.path("networks").path("backend").path("command").isMissingNode());
    }

    @Test
    void usesNetworkModeForBuiltInNetworks() throws Exception {
        JsonNode root = convert("docker run --net=host --name n nginx");

        assertEquals("host", service(root, "n").path("network_mode").asText());
        assertTrue(root.path("networks").isMissingNode());
    }

    @Test
    void acceptsEqualsAndAttachedForms() throws Exception {
        JsonNode web = service(convert("docker run --name=web -p8080:80 -p=9090:90 --restart=unless-stopped -it nginx"), "web");

        assertEquals("8080:80", web.path("ports").get(0).asText());
        assertEquals("9090:90", web.path("ports").get(1).asText());
        assertEquals("unless-stopped", web.path("restart").asText());
        assertTrue(web.path("stdin_open").asBoolean());
        assertTrue(web.path("tty").asBoolean());
    }

    @Test
    void handlesLineContinuationsAndSudo() throws Exception {
        JsonNode web = service(convert("sudo docker run \\\n  --name web \\\n  -p 80:80 \\\n  nginx"), "web");

        assertEquals("nginx", web.path("image").asText());
    }

    @Test
    void derivesServiceNameFromImageWhenNameMissing() throws Exception {
        JsonNode root = convert("docker run registry.example.com/team/My_App:2.1");

        assertFalse(service(root, "my_app").isMissingNode());
    }

    @Test
    void reportsIgnoredOptionsInsteadOfDroppingThemSilently() {
        DockerRunConverter.Result result = DockerRunConverter.convert("docker run --rm --ulimit nofile=1024 nginx");

        assertEquals(2, result.warnings().size(), result.warnings().toString());
    }

    @Test
    void rejectsInvalidCommands() {
        assertThrows(IllegalArgumentException.class, () -> DockerRunConverter.convert("podman run nginx"));
        assertThrows(IllegalArgumentException.class, () -> DockerRunConverter.convert("docker ps"));
        assertThrows(IllegalArgumentException.class, () -> DockerRunConverter.convert("docker run -d --name x"));
        assertThrows(IllegalArgumentException.class, () -> DockerRunConverter.convert("docker run -e"));
    }
}
