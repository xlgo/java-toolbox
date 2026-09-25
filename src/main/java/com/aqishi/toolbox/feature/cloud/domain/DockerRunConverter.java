package com.aqishi.toolbox.feature.cloud.domain;

import com.aqishi.toolbox.util.ShellQuote;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把一条 {@code docker run} 命令转换成等价的 docker-compose 服务定义。
 *
 * <p>早先的实现用正则切词、手工拼 YAML，出过这些错：{@code -e KEY="a b"} 被拆成两段、后一段成了镜像名；
 * 不认识的带值参数（{@code -w /app}、{@code --env-file .env}）只跳过参数本身，值被当成镜像；
 * {@code command:} 被写到顶层 {@code networks:} 下面；{@code MSG=a: b} 这类值不加引号变成了映射。
 * 这里按 shell 规则切词，用参数表区分"带值"与"开关"，并交给 YAML 库序列化，所有字符串都会被正确加引号。</p>
 *
 * <p>无法对应到 Compose 的参数不会被静默吞掉，而是出现在 {@link Result#warnings()} 里。</p>
 */
public final class DockerRunConverter {

    /** 转换结果：YAML 文本与被忽略的参数说明。 */
    public record Result(String yaml, List<String> warnings) {
    }

    /** 不带值的开关。 */
    private static final Set<String> BOOLEAN_FLAGS = Set.of(
            "-d", "--detach", "--rm", "-i", "--interactive", "-t", "--tty", "--init",
            "--privileged", "--read-only", "-P", "--publish-all", "--no-healthcheck");

    /** 带值但 Compose 没有对应字段、或本工具不做转换的参数：跳过值并给出提示。 */
    private static final Set<String> IGNORED_VALUE_FLAGS = Set.of(
            "--log-opt", "--ulimit", "--mount", "--health-cmd", "--health-interval", "--health-retries",
            "--health-timeout", "--pid", "--ipc", "--runtime", "--gpus", "--stop-signal", "--stop-timeout",
            "--cidfile", "--pull", "--label-file", "--attach", "-a", "--memory-swap", "--cpu-shares", "-c",
            "--cpuset-cpus", "--group-add", "--sysctl", "--volumes-from", "--link", "--mac-address", "--ip");

    /** 带值的短参数，可以写成 {@code -p8080:80} 这种紧贴形式。 */
    private static final Set<Character> SHORT_VALUE_FLAGS = Set.of('p', 'v', 'e', 'h', 'w', 'u', 'm', 'l', 'a', 'c');

    /** 这些网络名表示网络模式而不是外部网络。 */
    private static final Set<String> NETWORK_MODES = Set.of("host", "none", "bridge", "default");

    private DockerRunConverter() {
    }

    /**
     * @throws IllegalArgumentException 不是 docker run 命令、引号未闭合或缺少镜像名
     */
    public static Result convert(String command) {
        List<String> words = ShellQuote.split(command == null ? "" : command.trim());
        int index = 0;
        if (index < words.size() && "sudo".equals(words.get(index))) {
            index++;
        }
        if (index >= words.size() || !"docker".equals(words.get(index))) {
            throw new IllegalArgumentException("Command must start with 'docker' or 'sudo docker'");
        }
        index++;
        if (index < words.size() && "container".equals(words.get(index))) {
            index++;
        }
        if (index >= words.size() || !"run".equals(words.get(index))) {
            throw new IllegalArgumentException("Only 'docker run' commands can be converted");
        }
        index++;

        Map<String, Object> service = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        String name = null;
        String network = null;
        String image = null;
        List<String> commandArgs = new ArrayList<>();

        while (index < words.size()) {
            String word = words.get(index);
            if (!word.startsWith("-") || "-".equals(word)) {
                image = word;
                commandArgs.addAll(words.subList(index + 1, words.size()));
                break;
            }
            if ("--".equals(word)) {
                index++;
                continue;
            }
            String flag = word;
            String inline = null;
            if (word.startsWith("--") && word.contains("=")) {
                flag = word.substring(0, word.indexOf('='));
                inline = word.substring(word.indexOf('=') + 1);
            } else if (!word.startsWith("--") && word.length() > 2) {
                if (SHORT_VALUE_FLAGS.contains(word.charAt(1))) {
                    // -p8080:80 或 -p=8080:80
                    flag = word.substring(0, 2);
                    inline = word.charAt(2) == '=' ? word.substring(3) : word.substring(2);
                } else if (expandShortCluster(word, service, warnings)) {
                    // -it / -dit 之类的开关组合
                    index++;
                    continue;
                }
            }

            if (BOOLEAN_FLAGS.contains(flag)) {
                applyBoolean(flag, service, warnings);
                index++;
                continue;
            }

            String value;
            if (inline != null) {
                value = inline;
                index++;
            } else if (index + 1 < words.size()) {
                value = words.get(index + 1);
                index += 2;
            } else {
                throw new IllegalArgumentException(flag + " requires a value");
            }

            switch (flag) {
                case "--name": name = value; break;
                case "-p": case "--publish": addToList(service, "ports", value); break;
                case "-v": case "--volume": addToList(service, "volumes", value); break;
                case "-e": case "--env": addToList(service, "environment", value); break;
                case "--env-file": addToList(service, "env_file", value); break;
                case "--restart": service.put("restart", value); break;
                case "--network": case "--net": network = value; break;
                case "-h": case "--hostname": service.put("hostname", value); break;
                case "-w": case "--workdir": service.put("working_dir", value); break;
                case "-u": case "--user": service.put("user", value); break;
                case "--entrypoint": service.put("entrypoint", value); break;
                case "--add-host": addToList(service, "extra_hosts", value); break;
                case "--cap-add": addToList(service, "cap_add", value); break;
                case "--cap-drop": addToList(service, "cap_drop", value); break;
                case "--dns": addToList(service, "dns", value); break;
                case "-l": case "--label": addToList(service, "labels", value); break;
                case "--device": addToList(service, "devices", value); break;
                case "--expose": addToList(service, "expose", value); break;
                case "--tmpfs": addToList(service, "tmpfs", value); break;
                case "--security-opt": addToList(service, "security_opt", value); break;
                case "-m": case "--memory": service.put("mem_limit", value); break;
                case "--cpus": service.put("cpus", value); break;
                case "--shm-size": service.put("shm_size", value); break;
                case "--platform": service.put("platform", value); break;
                case "--log-driver": service.put("logging", Map.of("driver", value)); break;
                default:
                    warnings.add(IGNORED_VALUE_FLAGS.contains(flag)
                            ? "ignored " + flag + " " + value
                            : "unknown option " + flag + " " + value + " (treated as taking a value)");
                    break;
            }
        }

        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("No image found in docker run command");
        }

        String serviceName = sanitizeServiceName(name, image);
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("image", image);
        if (name != null) {
            ordered.put("container_name", name);
        }
        ordered.putAll(service);
        if (!commandArgs.isEmpty()) {
            // 列表形式（exec form）原样保留每个参数，含空格也不会被重新切分。
            ordered.put("command", commandArgs);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> services = new LinkedHashMap<>();
        services.put(serviceName, ordered);
        root.put("services", services);

        if (network != null) {
            if (NETWORK_MODES.contains(network) || network.startsWith("container:")) {
                ordered.put("network_mode", network);
            } else {
                ordered.put("networks", List.of(network));
                root.put("networks", Map.of(network, Map.of("external", true)));
            }
        }

        return new Result(toYaml(root), Collections.unmodifiableList(warnings));
    }

    private static boolean expandShortCluster(String word, Map<String, Object> service, List<String> warnings) {
        for (int i = 1; i < word.length(); i++) {
            if (!BOOLEAN_FLAGS.contains("-" + word.charAt(i))) {
                return false;
            }
        }
        for (int i = 1; i < word.length(); i++) {
            applyBoolean("-" + word.charAt(i), service, warnings);
        }
        return true;
    }

    private static void applyBoolean(String flag, Map<String, Object> service, List<String> warnings) {
        switch (flag) {
            case "--privileged": service.put("privileged", true); break;
            case "--read-only": service.put("read_only", true); break;
            case "--init": service.put("init", true); break;
            case "-i": case "--interactive": service.put("stdin_open", true); break;
            case "-t": case "--tty": service.put("tty", true); break;
            case "--rm": warnings.add("ignored --rm (Compose manages container lifecycle)"); break;
            case "-P": case "--publish-all": warnings.add("ignored -P (list ports explicitly with -p)"); break;
            default: break; // -d：Compose 用 `up -d` 控制，无需字段
        }
    }

    @SuppressWarnings("unchecked")
    private static void addToList(Map<String, Object> service, String key, String value) {
        ((List<String>) service.computeIfAbsent(key, k -> new ArrayList<String>())).add(value);
    }

    /** 服务名只允许小写字母、数字、下划线、点和横线；取容器名，没有则取镜像名最后一段。 */
    private static String sanitizeServiceName(String name, String image) {
        String base = name;
        if (base == null || base.isEmpty()) {
            String withoutTag = image.contains("@") ? image.substring(0, image.indexOf('@')) : image;
            int slash = withoutTag.lastIndexOf('/');
            base = withoutTag.substring(slash + 1);
            int colon = base.indexOf(':');
            if (colon > 0) {
                base = base.substring(0, colon);
            }
        }
        String cleaned = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
        return cleaned.isEmpty() ? "app" : cleaned;
    }

    private static String toYaml(Map<String, Object> root) {
        try {
            YAMLMapper mapper = new YAMLMapper(YAMLFactory.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
                    .build());
            return mapper.writeValueAsString(root);
        } catch (Exception serialization) {
            throw new IllegalStateException("Unable to serialize compose YAML", serialization);
        }
    }
}
