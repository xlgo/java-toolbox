package com.aqishi.toolbox.feature.system.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 常见已知依赖冲突检测器：内置针对日志库、Servlet 容器规范、BouncyCastle、Hamcrest 等常见神坑的规则匹配。
 */
public final class KnownConflictDetector {

    public record Match(
            KnownConflictRule rule,
            ClasspathSource firstSource,
            ClasspathSource secondSource
    ) {}

    private static final List<KnownConflictRule> DEFAULT_RULES = List.of(
            new KnownConflictRule(
                    "slf4j-binding",
                    Pattern.compile("slf4j-log4j12", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("(log4j-to-slf4j|log4j-slf4j-impl|logback-classic)", Pattern.CASE_INSENSITIVE),
                    "tool.classpath.diagnostic.known.slf4j.category",
                    "tool.classpath.diagnostic.known.slf4j.desc",
                    "tool.classpath.diagnostic.known.slf4j.suggestion"
            ),
            new KnownConflictRule(
                    "commons-logging-jcl",
                    Pattern.compile("commons-logging(?!-api)", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("jcl-over-slf4j", Pattern.CASE_INSENSITIVE),
                    "tool.classpath.diagnostic.known.jcl.category",
                    "tool.classpath.diagnostic.known.jcl.desc",
                    "tool.classpath.diagnostic.known.jcl.suggestion"
            ),
            new KnownConflictRule(
                    "servlet-api-jakarta",
                    Pattern.compile("(javax\\.servlet-api|servlet-api)", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("jakarta\\.servlet-api", Pattern.CASE_INSENSITIVE),
                    "tool.classpath.diagnostic.known.servlet.category",
                    "tool.classpath.diagnostic.known.servlet.desc",
                    "tool.classpath.diagnostic.known.servlet.suggestion"
            ),
            new KnownConflictRule(
                    "log4j1-over-slf4j",
                    Pattern.compile("(?:^|/|\\\\)log4j-(?:1\\.|[0-9]\\.)[\\w.-]+\\.jar", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("log4j-over-slf4j", Pattern.CASE_INSENSITIVE),
                    "tool.classpath.diagnostic.known.log4j1.category",
                    "tool.classpath.diagnostic.known.log4j1.desc",
                    "tool.classpath.diagnostic.known.log4j1.suggestion"
            ),
            new KnownConflictRule(
                    "bouncycastle-version-split",
                    Pattern.compile("bcprov-jdk15on", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("bcprov-jdk18on", Pattern.CASE_INSENSITIVE),
                    "tool.classpath.diagnostic.known.bcprov.category",
                    "tool.classpath.diagnostic.known.bcprov.desc",
                    "tool.classpath.diagnostic.known.bcprov.suggestion"
            ),
            new KnownConflictRule(
                    "hamcrest-core-all",
                    Pattern.compile("hamcrest-core", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("hamcrest-all", Pattern.CASE_INSENSITIVE),
                    "tool.classpath.diagnostic.known.hamcrest.category",
                    "tool.classpath.diagnostic.known.hamcrest.desc",
                    "tool.classpath.diagnostic.known.hamcrest.suggestion"
            ),
            new KnownConflictRule(
                    "cglib-nodep",
                    Pattern.compile("cglib-[0-9]", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("cglib-nodep", Pattern.CASE_INSENSITIVE),
                    "tool.classpath.diagnostic.known.cglib.category",
                    "tool.classpath.diagnostic.known.cglib.desc",
                    "tool.classpath.diagnostic.known.cglib.suggestion"
            ),
            new KnownConflictRule(
                    "guava-jdk5",
                    Pattern.compile("guava-jdk5", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("(?:^|/|\\\\)guava-(?:[1-9][0-9])", Pattern.CASE_INSENSITIVE),
                    "tool.classpath.diagnostic.known.guava.category",
                    "tool.classpath.diagnostic.known.guava.desc",
                    "tool.classpath.diagnostic.known.guava.suggestion"
            ),
            new KnownConflictRule(
                    "stax-api",
                    Pattern.compile("stax-api", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("geronimo-stax-api", Pattern.CASE_INSENSITIVE),
                    "tool.classpath.diagnostic.known.stax.category",
                    "tool.classpath.diagnostic.known.stax.desc",
                    "tool.classpath.diagnostic.known.stax.suggestion"
            )
    );

    private final List<KnownConflictRule> rules;

    public KnownConflictDetector() {
        this(DEFAULT_RULES);
    }

    public KnownConflictDetector(List<KnownConflictRule> rules) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    public List<Match> detect(List<ClasspathSource> sources) {
        if (sources == null || sources.size() < 2) {
            return Collections.emptyList();
        }

        List<Match> matches = new ArrayList<>();
        int count = sources.size();

        for (int i = 0; i < count; i++) {
            ClasspathSource srcA = sources.get(i);
            String nameA = srcA.fullPath();
            for (int j = i + 1; j < count; j++) {
                ClasspathSource srcB = sources.get(j);
                String nameB = srcB.fullPath();

                for (KnownConflictRule rule : rules) {
                    if (rule.matches(nameA, nameB)) {
                        matches.add(new Match(rule, srcA, srcB));
                    }
                }
            }
        }

        return matches;
    }
}
