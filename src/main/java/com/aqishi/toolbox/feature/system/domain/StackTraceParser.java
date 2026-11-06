package com.aqishi.toolbox.feature.system.domain;

import java.util.*;
import java.util.regex.*;

/**
 * 从任意文本（纯堆栈或夹杂日志）中提取 Java 异常堆栈。
 *
 * <p>只做结构化解析，不依赖界面文案，便于独立测试。识别范围：异常头行（可带 {@code Exception in thread}
 * 前缀与多行消息）、{@code at} 帧（含模块/类加载器前缀和 logback 打包信息）、{@code Caused by:}、
 * {@code Suppressed:} 及 {@code ... N more}。</p>
 */
public final class StackTraceParser {
    /** 帧在异常链中的关系。 */
    public enum Relation {
        ROOT,
        CAUSED_BY,
        SUPPRESSED
    }

    /** 单个调用帧；{@code location} 为括号内的 "文件:行号" / "Native Method" 等原文。 */
    public record Frame(String className, String method, String location, String raw) {
        public String display() {
            return className + "." + method + "(" + location + ")";
        }
    }

    /** 异常链中的一个节点。{@code indent} 用于区分主链与被抑制异常内部的嵌套链。 */
    public record Block(
            Relation relation,
            int indent,
            String type,
            String message,
            List<Frame> frames,
            int omitted) {
        public String simpleType() {
            int dot = type.lastIndexOf('.');
            return dot < 0 ? type : type.substring(dot + 1);
        }

        public String header() {
            return message.isEmpty() ? type : type + ": " + message;
        }
    }

    /** 一个完整的顶层异常（含其 Caused by / Suppressed 链），按出现顺序排列。 */
    public record Trace(List<Block> blocks) {
        public Block top() {
            return blocks.get(0);
        }

        /** 主链：顶层异常及与之同缩进的 Caused by。 */
        public List<Block> mainChain() {
            int base = top().indent();
            List<Block> chain = new ArrayList<>();
            for (Block block : blocks)
                if (block.relation() != Relation.SUPPRESSED && block.indent() == base)
                    chain.add(block);
            return chain;
        }

        public Block rootCause() {
            List<Block> chain = mainChain();
            return chain.get(chain.size() - 1);
        }

        /** 去重签名：异常类型链 + 每层前 8 帧。 */
        public String signature() {
            StringBuilder key = new StringBuilder();
            for (Block block : blocks) {
                key.append(block.relation()).append(block.type()).append('|');
                block.frames().stream().limit(8).forEach(f -> key.append(f.display()).append(';'));
            }
            return key.toString();
        }
    }

    public static final int MAX_TRACES = 500;
    private static final int MAX_MESSAGE_LINES = 20;

    private static final String FQN = "(?:[A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*";
    private static final Pattern HEADER =
            Pattern.compile(
                    "^(\\s*)(?:Exception in thread \"[^\"]*\"\\s+)?(?:(Caused by|Suppressed):\\s*)?"
                            + "("
                            + FQN
                            + ")(?::\\s?(.*))?$");
    private static final Pattern FRAME =
            Pattern.compile("^\\s*at\\s+(?:\\S*/)?([^\\s/(]+)\\.([^\\s.(]+)\\(([^)]*)\\).*$");
    private static final Pattern MORE = Pattern.compile("^\\s*\\.\\.\\.\\s*(\\d+)\\s+(?:more|common frames omitted)\\s*$");
    private static final Pattern THROWABLE_NAME =
            Pattern.compile(".*(?:Exception|Error|Throwable|Fault)$");

    public List<Trace> parse(String text) {
        String[] lines = text.split("\\R", -1);
        List<Trace> traces = new ArrayList<>();
        List<Block> current = null;
        Builder block = null;
        for (int i = 0; i < lines.length && traces.size() < MAX_TRACES; i++) {
            String line = lines[i];
            Matcher frame = FRAME.matcher(line);
            if (block != null && frame.matches()) {
                block.frames.add(new Frame(frame.group(1), frame.group(2), frame.group(3), line.trim()));
                continue;
            }
            Matcher more = MORE.matcher(line);
            if (block != null && more.matches()) {
                block.omitted += Integer.parseInt(more.group(1));
                continue;
            }
            Matcher header = HEADER.matcher(line);
            if (header.matches() && isHeader(header, lines, i)) {
                Relation relation = relationOf(header.group(2));
                if (block != null) current.add(block.build());
                if (relation == Relation.ROOT || current == null) {
                    if (current != null && !current.isEmpty()) traces.add(new Trace(current));
                    current = new ArrayList<>();
                    relation = Relation.ROOT;
                }
                block = new Builder(relation, indentOf(header.group(1)), header.group(3), header.group(4));
                continue;
            }
            if (block != null && block.frames.isEmpty() && block.messageLines < MAX_MESSAGE_LINES) {
                // 帧出现之前的非空行视为多行异常消息
                if (!line.isBlank()) block.appendMessage(line.trim());
                continue;
            }
            if (block != null) {
                current.add(block.build());
                traces.add(new Trace(current));
                current = null;
                block = null;
            }
        }
        if (block != null) current.add(block.build());
        if (current != null && !current.isEmpty() && traces.size() < MAX_TRACES)
            traces.add(new Trace(current));
        // 没有任何帧的“异常头”多半是日志里的普通句子，丢弃
        traces.removeIf(t -> t.blocks().stream().allMatch(b -> b.frames().isEmpty()));
        return traces;
    }

    /** 名称形似异常类，或下一行紧跟 at 帧，才认定为异常头，避免误吞普通日志行。 */
    private static boolean isHeader(Matcher header, String[] lines, int index) {
        if (header.group(2) != null) return true;
        if (THROWABLE_NAME.matcher(header.group(3)).matches()) return true;
        return index + 1 < lines.length && FRAME.matcher(lines[index + 1]).matches();
    }

    private static Relation relationOf(String token) {
        if (token == null) return Relation.ROOT;
        return token.startsWith("Caused") ? Relation.CAUSED_BY : Relation.SUPPRESSED;
    }

    private static int indentOf(String whitespace) {
        return whitespace.replace("\t", "    ").length();
    }

    private static final class Builder {
        final Relation relation;
        final int indent;
        final String type;
        final StringBuilder message;
        final List<Frame> frames = new ArrayList<>();
        int omitted;
        int messageLines;

        Builder(Relation relation, int indent, String type, String message) {
            this.relation = relation;
            this.indent = indent;
            this.type = type;
            this.message = new StringBuilder(message == null ? "" : message.trim());
        }

        void appendMessage(String line) {
            if (message.length() > 0) message.append('\n');
            message.append(line);
            messageLines++;
        }

        Block build() {
            return new Block(relation, indent, type, message.toString(), List.copyOf(frames), omitted);
        }
    }
}
