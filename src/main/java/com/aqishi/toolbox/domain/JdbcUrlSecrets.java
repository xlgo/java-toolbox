package com.aqishi.toolbox.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 JDBC URL 里内嵌的密码拆出来交给保险库，URL 本身只留占位符。
 *
 * <p>很多人把密码直接写进 URL（{@code ?password=}、SQL Server 的 {@code ;password=}、
 * {@code //user:pass@host}、Oracle 的 {@code thin:user/pass@}），连接配置因此把明文密码
 * 连同 URL 一起写进本地偏好，控制台日志也会原样打印出来。这里识别这几种写法：
 * {@link #split} 把密码值换成 {@code {{vault:名称}}} 占位符并返回拆出的值，
 * {@link #restore} 在连接前把值填回去，{@link #redact} 给日志用。</p>
 *
 * <p>只替换值、不解码：保险库里存的是 URL 中的原始字符串，还原后与用户输入逐字相同。
 * 对已经是占位符的 URL 再调用 {@link #split} 是幂等的。</p>
 */
public final class JdbcUrlSecrets {

    /** 拆出的密钥在配置密钥表里的键前缀，与连接密码 {@code password} 区分开。 */
    public static final String FIELD_PREFIX = "url:";

    private static final String MARKER_START = "{{vault:";
    private static final Pattern MARKER = Pattern.compile("\\{\\{vault:([^}]+)}}");
    private static final String REDACTED = "******";

    /**
     * 参数名里带 password / passwd / pwd / secret / token 的键值对。分隔符覆盖 {@code ? & ;}，
     * 以及 DB2 在库名后用的 {@code :}。值到下一个分隔符为止；SQL Server 的 {@code {...}} 整体作为值。
     */
    private static final Pattern PARAM = Pattern.compile(
            // 占位符必须作为整体先匹配，否则 {...} 分支只吃到 "{{vault:x}" 半截，二次拆分就不幂等了
            "(?i)([?&;:])([A-Za-z0-9_.\\-]*(?:password|passwd|pwd|secret|token)[A-Za-z0-9_.\\-]*)"
                    + "=(\\{\\{vault:[^}]+}}|\\{[^}]*}|[^&;#]*)");
    /** {@code scheme://user:pass@host} 形式的 userinfo 密码。 */
    private static final Pattern USERINFO = Pattern.compile("(//[^/@:?;]+):([^@/?;]*)@");
    /** Oracle {@code jdbc:oracle:thin:user/pass@host} 形式。 */
    private static final Pattern ORACLE = Pattern.compile("(?i)(jdbc:oracle:(?:thin|oci8?):[^/@:]*)/([^@]*)@");

    private JdbcUrlSecrets() {
    }

    /** 拆分结果：带占位符的 URL 与按字段名存放的密码值（键已带 {@link #FIELD_PREFIX}）。 */
    public static final class Split {
        private final String maskedUrl;
        private final Map<String, String> secrets;

        private Split(String maskedUrl, Map<String, String> secrets) {
            this.maskedUrl = maskedUrl;
            this.secrets = Collections.unmodifiableMap(secrets);
        }

        public String maskedUrl() {
            return maskedUrl;
        }

        public Map<String, String> secrets() {
            return secrets;
        }
    }

    public static Split split(String url) {
        if (url == null || url.isEmpty()) {
            return new Split(url, new LinkedHashMap<>());
        }
        Map<String, String> secrets = new LinkedHashMap<>();
        String masked = replace(url, ORACLE, 2, name -> "oracle", secrets);
        masked = replace(masked, USERINFO, 2, name -> "userinfo", secrets);
        masked = replace(masked, PARAM, 3, name -> name, secrets);
        return new Split(masked, secrets);
    }

    /** 把占位符换回保险库中的值；缺值的占位符原样保留，由 {@link #hasPlaceholders} 检查。 */
    public static String restore(String maskedUrl, Map<String, String> fields) {
        if (maskedUrl == null || fields == null || fields.isEmpty() || !maskedUrl.contains(MARKER_START)) {
            return maskedUrl;
        }
        Matcher matcher = MARKER.matcher(maskedUrl);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = fields.get(FIELD_PREFIX + matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : matcher.group()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static boolean hasPlaceholders(String url) {
        return url != null && url.contains(MARKER_START);
    }

    /** 日志与提示用：密码值（无论明文还是占位符）一律显示为星号。 */
    public static String redact(String url) {
        if (url == null) {
            return null;
        }
        return MARKER.matcher(split(url).maskedUrl()).replaceAll(Matcher.quoteReplacement(REDACTED));
    }

    private interface Namer {
        String name(String key);
    }

    private static String replace(String url, Pattern pattern, int valueGroup, Namer namer,
                                  Map<String, String> secrets) {
        Matcher matcher = pattern.matcher(url);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = matcher.group(valueGroup);
            // 空值不是密码；已经是占位符的说明拆过了，保持幂等
            if (value.isEmpty() || MARKER.matcher(value).matches()) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String name = uniqueName(namer.name(valueGroup == 3 ? matcher.group(2) : null), secrets);
            secrets.put(FIELD_PREFIX + name, value);
            String whole = matcher.group();
            int valueStart = matcher.start(valueGroup) - matcher.start();
            int valueEnd = matcher.end(valueGroup) - matcher.start();
            String replaced = whole.substring(0, valueStart) + MARKER_START + name + "}}" + whole.substring(valueEnd);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replaced));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 同名参数出现多次时追加序号，保证每个值都能各自还原。 */
    private static String uniqueName(String base, Map<String, String> secrets) {
        String name = base;
        for (int i = 2; secrets.containsKey(FIELD_PREFIX + name); i++) {
            name = base + "#" + i;
        }
        return name;
    }
}
