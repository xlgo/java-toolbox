package com.aqishi.toolbox.feature.system.domain;

import com.aqishi.toolbox.feature.system.domain.StackTraceParser.*;
import com.aqishi.toolbox.util.I18n;

import java.util.*;

/**
 * 基于 {@link StackTraceParser} 的结果生成排查报告：异常链、根因、首个业务帧、排查提示和折叠后的精简堆栈。
 *
 * <p>业务帧判定：填写了业务包前缀时以前缀为准；否则把不属于常见 JDK / 框架 / 代理生成类的帧视为业务帧。</p>
 */
public final class StackTraceReport {
    private static final List<String> FRAMEWORK_PREFIXES =
            List.of(
                    "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.", "kotlin.", "kotlinx.",
                    "scala.", "groovy.", "org.springframework.", "org.apache.", "org.hibernate.",
                    "org.mybatis.", "com.baomidou.", "io.netty.", "reactor.", "io.reactivex.",
                    "io.undertow.", "org.eclipse.jetty.", "org.glassfish.", "com.fasterxml.",
                    "com.google.", "com.zaxxer.", "com.alibaba.druid.", "com.alibaba.fastjson",
                    "com.mysql.", "org.postgresql.", "oracle.jdbc.", "com.microsoft.sqlserver.",
                    "org.h2.", "org.junit.", "junit.", "org.mockito.", "org.testng.", "feign.",
                    "io.grpc.", "okhttp3.", "retrofit2.", "io.micrometer.", "ch.qos.logback.",
                    "org.slf4j.", "org.aspectj.", "net.bytebuddy.", "lombok.", "io.lettuce.",
                    "redis.clients.", "org.redisson.", "io.opentelemetry.", "brave.", "org.jboss.",
                    "com.netflix.", "io.github.resilience4j.", "org.quartz.", "com.alibaba.csp.",
                    "com.alibaba.nacos.", "org.elasticsearch.", "co.elastic.", "org.thymeleaf.",
                    "freemarker.", "io.vertx.", "io.quarkus.", "io.micronaut.");
    private static final List<String> GENERATED_MARKERS =
            List.of("$$", "$Proxy", "GeneratedMethodAccessor", "CGLIB", "ByteBuddy", "$Lambda");
    /** 只是包装，真正原因在 Caused by 中的异常。 */
    private static final Set<String> WRAPPERS =
            Set.of(
                    "InvocationTargetException", "UndeclaredThrowableException",
                    "ExecutionException", "CompletionException", "UncheckedExecutionException",
                    "ExceptionInInitializerError", "NestedServletException",
                    "ServletException", "UncheckedIOException");
    /** 内置排查提示的异常简单类名，对应资源键 {@code stack.hint.<SimpleName>}。 */
    private static final Set<String> HINTS =
            Set.of(
                    "NullPointerException", "ClassCastException", "ClassNotFoundException",
                    "NoClassDefFoundError", "NoSuchMethodError", "NoSuchFieldError",
                    "AbstractMethodError", "IncompatibleClassChangeError",
                    "UnsupportedClassVersionError", "StackOverflowError",
                    "ConcurrentModificationException", "IllegalArgumentException",
                    "IllegalStateException", "IndexOutOfBoundsException",
                    "ArrayIndexOutOfBoundsException", "StringIndexOutOfBoundsException",
                    "NumberFormatException", "UnsupportedOperationException",
                    "ArithmeticException", "DateTimeParseException", "SocketTimeoutException",
                    "ConnectException", "UnknownHostException", "SSLHandshakeException",
                    "SocketException", "SQLSyntaxErrorException",
                    "SQLIntegrityConstraintViolationException", "DataIntegrityViolationException",
                    "DuplicateKeyException", "SQLTransientConnectionException",
                    "CannotGetJdbcConnectionException", "CannotAcquireLockException",
                    "BadSqlGrammarException", "BeanCreationException",
                    "NoSuchBeanDefinitionException", "NoUniqueBeanDefinitionException",
                    "UnsatisfiedDependencyException", "BeanCurrentlyInCreationException",
                    "LazyInitializationException", "TransactionSystemException",
                    "HttpMessageNotReadableException", "MethodArgumentNotValidException",
                    "MismatchedInputException", "UnrecognizedPropertyException",
                    "InvalidDefinitionException", "JsonParseException", "InterruptedException",
                    "TimeoutException", "RejectedExecutionException", "FileNotFoundException",
                    "AccessDeniedException", "BindException", "OptimisticLockException",
                    "TooManyResultsException", "BindingException");

    private final List<String> appPrefixes;
    private final boolean fold;

    public StackTraceReport(String appPrefixes, boolean fold) {
        this.appPrefixes =
                Arrays.stream(appPrefixes.split("[,;\\s]+"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
        this.fold = fold;
    }

    public String analyze(String text) {
        if (text.length() > 2_000_000) throw new IllegalArgumentException(I18n.get("devtools.inputLimit"));
        List<Trace> traces = new StackTraceParser().parse(text);
        if (traces.isEmpty()) throw new IllegalArgumentException(I18n.get("stack.empty"));
        LinkedHashMap<String, List<Trace>> groups = new LinkedHashMap<>();
        for (Trace trace : traces)
            groups.computeIfAbsent(trace.signature(), k -> new ArrayList<>()).add(trace);
        StringBuilder out = new StringBuilder();
        out.append(I18n.get("stack.summary", traces.size(), groups.size())).append('\n');
        if (traces.size() >= StackTraceParser.MAX_TRACES)
            out.append(I18n.get("stack.truncated", StackTraceParser.MAX_TRACES)).append('\n');
        int index = 1;
        for (List<Trace> group : groups.values()) report(out, index++, group.get(0), group.size());
        return out.toString();
    }

    /** 是否业务帧；公开以便单测与后续工具复用。 */
    public boolean isAppFrame(Frame frame) {
        String name = frame.className();
        for (String marker : GENERATED_MARKERS) if (name.contains(marker)) return false;
        if (!appPrefixes.isEmpty()) return appPrefixes.stream().anyMatch(name::startsWith);
        return FRAMEWORK_PREFIXES.stream().noneMatch(name::startsWith);
    }

    private void report(StringBuilder out, int index, Trace trace, int count) {
        out.append("\n========== ").append(I18n.get("stack.group", index, count)).append(" ==========\n");
        List<Block> chain = trace.mainChain();
        Block root = trace.rootCause();
        out.append(I18n.get("stack.chain")).append('\n');
        for (int i = 0; i < chain.size(); i++) {
            Block block = chain.get(i);
            out.append("  ").append(i == 0 ? "" : "└─ Caused by: ").append(firstLine(block.header()));
            if (block == root && chain.size() > 1) out.append("  ").append(I18n.get("stack.rootMarker"));
            out.append('\n');
        }
        out.append(I18n.get("stack.rootCause", firstLine(root.header()))).append('\n');
        Frame app = firstAppFrame(chain);
        out.append(app == null ? I18n.get("stack.noAppFrame") : I18n.get("stack.firstAppFrame", app.display()))
                .append('\n');
        appendAdvice(out, trace, root);
        out.append('\n').append(I18n.get("stack.simplified")).append('\n');
        for (Block block : trace.blocks()) appendBlock(out, block);
    }

    /** 根因帧里找不到业务帧时（例如抛自驱动内部），回溯外层异常的帧。 */
    private Frame firstAppFrame(List<Block> chain) {
        for (int i = chain.size() - 1; i >= 0; i--)
            for (Frame frame : chain.get(i).frames()) if (isAppFrame(frame)) return frame;
        return null;
    }

    private void appendAdvice(StringBuilder out, Trace trace, Block root) {
        LinkedHashSet<String> advice = new LinkedHashSet<>();
        String rootHint = hintKey(root);
        if (rootHint != null) advice.add(I18n.get(rootHint));
        for (Block block : trace.mainChain()) {
            if (block != root && WRAPPERS.contains(block.simpleType()))
                advice.add(I18n.get("stack.wrapper", block.simpleType()));
            String key = hintKey(block);
            if (key != null) advice.add(I18n.get(key));
        }
        out.append(I18n.get("stack.advice")).append('\n');
        if (advice.isEmpty()) out.append("  - ").append(I18n.get("stack.noAdvice")).append('\n');
        for (String line : advice) out.append("  - ").append(line).append('\n');
    }

    private static String hintKey(Block block) {
        String name = block.simpleType();
        if (name.equals("OutOfMemoryError")) {
            String message = block.message();
            if (message.contains("Metaspace")) return "stack.hint.oom.metaspace";
            if (message.contains("GC overhead")) return "stack.hint.oom.gcOverhead";
            if (message.contains("Direct buffer")) return "stack.hint.oom.direct";
            if (message.contains("native thread")) return "stack.hint.oom.thread";
            return "stack.hint.oom.heap";
        }
        return HINTS.contains(name) ? "stack.hint." + name : null;
    }

    private void appendBlock(StringBuilder out, Block block) {
        String pad = " ".repeat(block.indent());
        String prefix =
                switch (block.relation()) {
                    case ROOT -> "";
                    case CAUSED_BY -> "Caused by: ";
                    case SUPPRESSED -> "Suppressed: ";
                };
        out.append(pad).append(prefix).append(block.header()).append('\n');
        int folded = 0;
        List<Frame> frames = block.frames();
        for (int i = 0; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            // 抛出点（第一帧）即使属于框架也保留，便于看清异常来源
            if (fold && i > 0 && !isAppFrame(frame)) {
                folded++;
                continue;
            }
            folded = flushFolded(out, pad, folded);
            out.append(pad).append("    at ").append(frame.display());
            if (isAppFrame(frame)) out.append("  ◀");
            out.append('\n');
        }
        flushFolded(out, pad, folded);
        if (block.omitted() > 0) out.append(pad).append("    ... ").append(block.omitted()).append(" more\n");
    }

    private static int flushFolded(StringBuilder out, String pad, int folded) {
        if (folded > 0) out.append(pad).append("    ").append(I18n.get("stack.folded", folded)).append('\n');
        return 0;
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline) + " …";
        return line.length() > 300 ? line.substring(0, 300) + " …" : line;
    }
}
