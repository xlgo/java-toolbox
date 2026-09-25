package com.aqishi.toolbox.feature.system.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 逐行保留的 hosts 文件模型。
 *
 * <p>早先的保存按表格内容整份重写，丢掉了：注释与空行、除 {@code ::1} 以外的 IPv6 条目、
 * Docker Desktop 的托管区段标记，还把同一行的别名（{@code 127.0.0.1 localhost localhost.localdomain}）
 * 挪进了注释。这里把文件拆成"规则行"和"其他行"：其他行原样写回；规则行按编号与编辑结果对应，
 * 未改动的规则行保持原文，删除的去掉，新增的追加在末尾。换行符沿用原文件。</p>
 */
public final class HostsFile {

    private static final Pattern IPV4 = Pattern.compile(
            "((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*(%\\w+)?");

    /** 一条解析规则。{@code id} 为 {@code null} 表示用户新加、原文件中不存在。 */
    public record Entry(Integer id, boolean enabled, String ip, List<String> hosts, String comment) {
        public Entry {
            hosts = List.copyOf(hosts);
            comment = comment == null ? "" : comment.trim();
            ip = ip == null ? "" : ip.trim();
        }

        /** 表格里的域名列：别名用空格分隔。 */
        public String hostsText() {
            return String.join(" ", hosts);
        }

        String format() {
            StringBuilder line = new StringBuilder();
            if (!enabled) {
                line.append("# ");
            }
            line.append(ip).append('\t').append(hostsText());
            if (!comment.isEmpty()) {
                line.append("\t# ").append(comment);
            }
            return line.toString();
        }

        boolean sameContentAs(Entry other) {
            return enabled == other.enabled && ip.equals(other.ip) && hosts.equals(other.hosts)
                    && comment.equals(other.comment);
        }
    }

    private final List<String> lines;
    private final Map<Integer, Entry> entriesByLine;
    private final String lineSeparator;

    private HostsFile(List<String> lines, Map<Integer, Entry> entriesByLine, String lineSeparator) {
        this.lines = lines;
        this.entriesByLine = entriesByLine;
        this.lineSeparator = lineSeparator;
    }

    public static HostsFile parse(String text) {
        String content = text == null ? "" : text;
        String separator = content.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\r?\n", -1)));
        // split 在末尾换行后会多出一个空串；记住它，写回时保持"文件以换行结尾"的状态。
        Map<Integer, Entry> entries = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Entry entry = parseLine(i, lines.get(i));
            if (entry != null) {
                entries.put(i, entry);
            }
        }
        return new HostsFile(lines, entries, separator);
    }

    /** 文件中的全部规则，按出现顺序。 */
    public List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Entry entry = entriesByLine.get(i);
            if (entry != null) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 按编辑结果生成新的文件内容。
     *
     * @param edited 编辑后的全部规则：带 id 的对应原有行（没出现的 id 视为已删除），id 为 null 的是新增
     */
    public String render(List<Entry> edited) {
        Map<Integer, Entry> byId = new HashMap<>();
        List<Entry> added = new ArrayList<>();
        for (Entry entry : edited) {
            if (entry.ip().isEmpty() || entry.hosts().isEmpty()) {
                continue;
            }
            if (entry.id() == null) {
                added.add(entry);
            } else {
                byId.put(entry.id(), entry);
            }
        }

        List<String> output = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Entry original = entriesByLine.get(i);
            if (original == null) {
                output.add(lines.get(i));
                continue;
            }
            Entry replacement = byId.get(original.id());
            if (replacement == null) {
                continue;
            }
            output.add(replacement.sameContentAs(original) ? lines.get(i) : replacement.format());
        }

        // 新增规则插在结尾空行之前，保持"以换行结尾"。
        int insertAt = output.size();
        while (insertAt > 0 && output.get(insertAt - 1).isEmpty()) {
            insertAt--;
        }
        List<String> tail = new ArrayList<>(output.subList(insertAt, output.size()));
        output = new ArrayList<>(output.subList(0, insertAt));
        for (Entry entry : added) {
            output.add(entry.format());
        }
        output.addAll(tail.isEmpty() && !added.isEmpty() ? List.of("") : tail);
        return String.join(lineSeparator, output);
    }

    /** 把表格里的一行转成规则；域名列可含多个用空白分隔的别名。 */
    public static Entry entry(Integer id, boolean enabled, String ip, String hostsText, String comment) {
        String hosts = hostsText == null ? "" : hostsText.trim();
        return new Entry(id, enabled, ip, hosts.isEmpty() ? List.of() : Arrays.asList(hosts.split("\\s+")), comment);
    }

    public static boolean isIpAddress(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return IPV4.matcher(value).matches() || (value.indexOf(':') >= 0 && IPV6.matcher(value).matches());
    }

    private static Entry parseLine(int index, String line) {
        String text = line.trim();
        boolean enabled = true;
        if (text.startsWith("#")) {
            enabled = false;
            text = text.substring(1).trim();
        }
        String comment = "";
        int hash = text.indexOf('#');
        if (hash >= 0) {
            comment = text.substring(hash + 1).trim();
            text = text.substring(0, hash).trim();
        }
        if (text.isEmpty()) {
            return null;
        }
        String[] tokens = text.split("\\s+");
        if (tokens.length < 2 || !isIpAddress(tokens[0])) {
            return null;
        }
        return new Entry(index, enabled, tokens[0], Arrays.asList(tokens).subList(1, tokens.length), comment);
    }
}
