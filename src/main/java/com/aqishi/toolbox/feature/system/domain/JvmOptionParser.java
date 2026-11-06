package com.aqishi.toolbox.feature.system.domain;

import java.util.*;
import java.util.regex.*;

/**
 * JVM 启动参数分词与结构化解析器。
 *
 * <p>支持解析单行 shell 命令、环境变量赋值、多行参数列表及行内注释；
 * 准确识别标准参数、-X 参数、-XX 参数、系统属性（-D）与 Agent 参数。</p>
 */
public final class JvmOptionParser {

    public enum OptionType {
        STANDARD,      // 标准参数如 -server, -ea
        NON_STANDARD,  // -X 系列如 -Xms, -Xmx, -Xss, -Xlog
        ADVANCED_XX,   // -XX 系列如 -XX:+UseG1GC, -XX:MaxRAMPercentage=75
        SYSTEM_PROP,   // -Dkey=value
        AGENT,         // -javaagent, -agentlib, -agentpath
        MODULE_ACCESS, // --add-opens, --add-exports 等
        OTHER_JVM      // 其他 JVM 相关选项
    }

    public record OptionToken(
            OptionType type,
            String raw,
            String key,
            String value,
            boolean isBooleanFlag,
            boolean booleanValue) {

        public static OptionToken standard(String raw, String name) {
            return new OptionToken(OptionType.STANDARD, raw, name, "", false, false);
        }

        public static OptionToken nonStandard(String raw, String key, String value) {
            return new OptionToken(OptionType.NON_STANDARD, raw, key, value, false, false);
        }

        public static OptionToken advancedBool(String raw, String name, boolean value) {
            return new OptionToken(OptionType.ADVANCED_XX, raw, name, "", true, value);
        }

        public static OptionToken advancedVal(String raw, String name, String value) {
            return new OptionToken(OptionType.ADVANCED_XX, raw, name, value, false, false);
        }

        public static OptionToken systemProp(String raw, String key, String value) {
            return new OptionToken(OptionType.SYSTEM_PROP, raw, key, value, false, false);
        }

        public static OptionToken agent(String raw, String type, String pathAndArgs) {
            return new OptionToken(OptionType.AGENT, raw, type, pathAndArgs, false, false);
        }

        public static OptionToken module(String raw, String key, String value) {
            return new OptionToken(OptionType.MODULE_ACCESS, raw, key, value, false, false);
        }

        public static OptionToken other(String raw, String key, String value) {
            return new OptionToken(OptionType.OTHER_JVM, raw, key, value, false, false);
        }
    }

    public record ParseResult(List<OptionToken> options, List<String> ignoredTokens) {}

    private static final Pattern SHELL_ENV = Pattern.compile("^[A-Za-z0-9_]+=\\s*['\"]?(.*)['\"]?$");
    private static final Pattern SIZE_PARAM = Pattern.compile("^(-X(?:ms|mx|mn|ss|maxjitcodesize))([0-9]+[kKmMgGtT]?)$");
    private static final Pattern XX_BOOL = Pattern.compile("^-XX:([+-])([A-Za-z0-9_]+)$");
    private static final Pattern XX_VAL = Pattern.compile("^-XX:([A-Za-z0-9_]+)=(.*)$");
    private static final Pattern SYS_PROP = Pattern.compile("^-D([^=]+)(?:=(.*))?$");
    private static final Pattern AGENT_PAT = Pattern.compile("^(-javaagent|-agentlib|-agentpath):(.*)$");
    private static final Pattern MODULE_PAT = Pattern.compile("^(--add-opens|--add-exports|--add-reads|--add-modules|--patch-module)(?:=(.*))?$");

    private static final Set<String> KNOWN_STANDARD = Set.of(
            "-server", "-client", "-ea", "-enableassertions", "-da", "-disableassertions",
            "-esa", "-enablesystemassertions", "-dsa", "-disablesystemassertions",
            "-d64", "-d32", "-verbose", "-showversion", "-version", "-help", "-?", "-X"
    );

    private static final Set<String> NON_JVM_COMMANDS = Set.of(
            "java", "nohup", "sudo", "exec", "&", ">", ">>", "2>&1", "<", "|", "bash", "sh"
    );

    public ParseResult parse(String text) {
        if (text == null || text.isBlank()) {
            return new ParseResult(List.of(), List.of());
        }

        List<String> rawTokens = extractTokens(text);
        List<OptionToken> options = new ArrayList<>();
        List<String> ignored = new ArrayList<>();

        for (int i = 0; i < rawTokens.size(); i++) {
            String token = rawTokens.get(i);

            // 过滤前后双引号或单引号
            token = stripQuotes(token);

            if (token.isEmpty()) continue;

            // 检查 shell 常见命令
            if (NON_JVM_COMMANDS.contains(token) || token.startsWith("1>") || token.startsWith("2>")) {
                ignored.add(token);
                continue;
            }

            // 处理 -jar 或 -cp / -classpath
            if (token.equals("-jar") || token.equals("-cp") || token.equals("-classpath") || token.equals("-p") || token.equals("--module-path")) {
                ignored.add(token);
                if (i + 1 < rawTokens.size()) {
                    ignored.add(rawTokens.get(++i));
                }
                continue;
            }

            // 匹配 --add-opens 等需要跨 token 或等号连接的模块指令
            Matcher modMatcher = MODULE_PAT.matcher(token);
            if (modMatcher.matches()) {
                String key = modMatcher.group(1);
                String val = modMatcher.group(2);
                if (val == null && i + 1 < rawTokens.size() && !rawTokens.get(i + 1).startsWith("-")) {
                    val = rawTokens.get(++i);
                }
                options.add(OptionToken.module(token, key, stripQuotes(val)));
                continue;
            }

            // 匹配 -D 系统属性
            Matcher sysMatcher = SYS_PROP.matcher(token);
            if (sysMatcher.matches()) {
                String key = sysMatcher.group(1);
                String val = sysMatcher.group(2);
                options.add(OptionToken.systemProp(token, key, stripQuotes(val)));
                continue;
            }

            // 匹配 Agent
            Matcher agentMatcher = AGENT_PAT.matcher(token);
            if (agentMatcher.matches()) {
                options.add(OptionToken.agent(token, agentMatcher.group(1), agentMatcher.group(2)));
                continue;
            }

            // 匹配 -XX:+Flag / -XX:-Flag
            Matcher xxBoolMatcher = XX_BOOL.matcher(token);
            if (xxBoolMatcher.matches()) {
                boolean boolVal = xxBoolMatcher.group(1).equals("+");
                options.add(OptionToken.advancedBool(token, xxBoolMatcher.group(2), boolVal));
                continue;
            }

            // 匹配 -XX:Key=Value
            Matcher xxValMatcher = XX_VAL.matcher(token);
            if (xxValMatcher.matches()) {
                options.add(OptionToken.advancedVal(token, xxValMatcher.group(1), xxValMatcher.group(2)));
                continue;
            }

            // 匹配 -Xms / -Xmx / -Xss / -Xmn
            Matcher sizeMatcher = SIZE_PARAM.matcher(token);
            if (sizeMatcher.matches()) {
                options.add(OptionToken.nonStandard(token, sizeMatcher.group(1), sizeMatcher.group(2)));
                continue;
            }

            // 匹配其他常见 -X 参数（如 -Xlog:..., -Xbootclasspath:...）
            if (token.startsWith("-X") && token.length() > 2) {
                int colonIdx = token.indexOf(':');
                int eqIdx = token.indexOf('=');
                int sep = (colonIdx > 0 && eqIdx > 0) ? Math.min(colonIdx, eqIdx) : Math.max(colonIdx, eqIdx);
                if (sep > 0) {
                    options.add(OptionToken.nonStandard(token, token.substring(0, sep), token.substring(sep + 1)));
                } else {
                    options.add(OptionToken.nonStandard(token, token, ""));
                }
                continue;
            }

            // 匹配已知标准参数
            if (KNOWN_STANDARD.contains(token) || token.startsWith("-verbose:")) {
                options.add(OptionToken.standard(token, token));
                continue;
            }

            // 其他以减号开头的参数
            if (token.startsWith("-")) {
                options.add(OptionToken.other(token, token, ""));
            } else {
                ignored.add(token);
            }
        }

        return new ParseResult(Collections.unmodifiableList(options), Collections.unmodifiableList(ignored));
    }

    private List<String> extractTokens(String text) {
        List<String> tokens = new ArrayList<>();
        String[] lines = text.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                continue;
            }

            // 去除环境变量头部如 JAVA_OPTS= 或 export JAVA_OPTS=
            if (trimmed.startsWith("export ")) {
                trimmed = trimmed.substring(7).trim();
            }
            Matcher envMatcher = SHELL_ENV.matcher(trimmed);
            if (envMatcher.matches() && trimmed.contains("-")) {
                trimmed = envMatcher.group(1);
            }

            // 逐字扫描，按空格分词，尊重单双引号与行尾注释
            StringBuilder sb = new StringBuilder();
            boolean inQuote = false;
            char quoteChar = 0;

            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);

                if (!inQuote && (c == '#' || (c == '/' && i + 1 < trimmed.length() && trimmed.charAt(i + 1) == '/'))) {
                    // 行尾注释开始
                    break;
                }

                if ((c == '"' || c == '\'') && !inQuote) {
                    inQuote = true;
                    quoteChar = c;
                    sb.append(c);
                } else if (inQuote && c == quoteChar) {
                    inQuote = false;
                    quoteChar = 0;
                    sb.append(c);
                } else if (Character.isWhitespace(c) && !inQuote) {
                    if (sb.length() > 0) {
                        tokens.add(sb.toString());
                        sb.setLength(0);
                    }
                } else {
                    sb.append(c);
                }
            }
            if (sb.length() > 0) {
                tokens.add(sb.toString());
            }
        }
        return tokens;
    }

    private static String stripQuotes(String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            if (trimmed.length() >= 2) {
                return trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }
}
