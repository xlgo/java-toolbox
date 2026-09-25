package com.aqishi.toolbox.feature.generation.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 实体生成选项。不可变；用 {@link #builder()} 或 {@link #toBuilder()} 构造。
 *
 * @param packageName                包名，空表示不写 package 语句
 * @param tablePrefixes              要去掉的表名前缀（不区分大小写，取最长的一个匹配），如 {@code t_}、{@code sys_}
 * @param classSuffix                类名后缀，如 {@code DO}、{@code Entity}，可为空
 * @param style                      代码风格
 * @param lombokBuilder              LOMBOK 风格下是否加 {@code @Builder}
 * @param lombokNoArgsConstructor    LOMBOK 风格下是否加 {@code @NoArgsConstructor}
 * @param lombokAllArgsConstructor   LOMBOK 风格下是否加 {@code @AllArgsConstructor}
 * @param plainToString              PLAIN 风格下是否生成 {@code toString()}
 * @param annotations                持久化注解
 * @param jpaNamespace               JPA 注解所在的包
 * @param jpaAlwaysColumn            JPA 下是否总是写 {@code @Column(name = ...)}；否则只在需要时写
 * @param serializable               是否实现 {@code Serializable} 并声明 {@code serialVersionUID}
 * @param javadoc                    是否把表/列注释写成 Javadoc
 * @param legacyDate                 日期时间类型是否改用 {@code java.util.Date}
 * @param unsignedBigintAsBigInteger bigint unsigned 是否映射为 {@code BigInteger}
 * @param mybatisXml                 是否额外为每张表生成 MyBatis Mapper XML（resultMap + 列清单）
 */
public record EntityOptions(
        String packageName,
        List<String> tablePrefixes,
        String classSuffix,
        Style style,
        boolean lombokBuilder,
        boolean lombokNoArgsConstructor,
        boolean lombokAllArgsConstructor,
        boolean plainToString,
        Annotations annotations,
        JpaNamespace jpaNamespace,
        boolean jpaAlwaysColumn,
        boolean serializable,
        boolean javadoc,
        boolean legacyDate,
        boolean unsignedBigintAsBigInteger,
        boolean mybatisXml) {

    /** 代码风格 */
    public enum Style {
        /** Lombok {@code @Data} */
        LOMBOK,
        /** 普通 JavaBean：字段 + getter/setter */
        PLAIN,
        /** Java 16+ record */
        RECORD
    }

    /** 持久化注解 */
    public enum Annotations {
        NONE,
        JPA,
        MYBATIS_PLUS
    }

    /** JPA 注解包：Jakarta EE 9+ / Spring Boot 3 用 jakarta，更早的用 javax */
    public enum JpaNamespace {
        JAKARTA("jakarta.persistence"),
        JAVAX("javax.persistence");

        private final String packageName;

        JpaNamespace(String packageName) {
            this.packageName = packageName;
        }

        public String packageName() {
            return packageName;
        }
    }

    public EntityOptions {
        packageName = packageName == null ? "" : packageName.trim();
        tablePrefixes = tablePrefixes == null ? List.of() : List.copyOf(tablePrefixes);
        classSuffix = classSuffix == null ? "" : classSuffix.trim();
        style = Objects.requireNonNullElse(style, Style.LOMBOK);
        annotations = Objects.requireNonNullElse(annotations, Annotations.NONE);
        jpaNamespace = Objects.requireNonNullElse(jpaNamespace, JpaNamespace.JAKARTA);
    }

    /** 默认选项：Lombok @Data、无持久化注解、带 Javadoc */
    public static EntityOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.packageName = packageName;
        b.tablePrefixes = new ArrayList<>(tablePrefixes);
        b.classSuffix = classSuffix;
        b.style = style;
        b.lombokBuilder = lombokBuilder;
        b.lombokNoArgsConstructor = lombokNoArgsConstructor;
        b.lombokAllArgsConstructor = lombokAllArgsConstructor;
        b.plainToString = plainToString;
        b.annotations = annotations;
        b.jpaNamespace = jpaNamespace;
        b.jpaAlwaysColumn = jpaAlwaysColumn;
        b.serializable = serializable;
        b.javadoc = javadoc;
        b.legacyDate = legacyDate;
        b.unsignedBigintAsBigInteger = unsignedBigintAsBigInteger;
        b.mybatisXml = mybatisXml;
        return b;
    }

    /**
     * 把逗号/空白分隔的前缀串拆成列表，例如 {@code "t_, sys_"}。
     */
    public static List<String> parsePrefixes(String text) {
        List<String> prefixes = new ArrayList<>();
        if (text == null) {
            return prefixes;
        }
        for (String part : text.split("[,;\\s]+")) {
            if (!part.isEmpty()) {
                prefixes.add(part);
            }
        }
        return prefixes;
    }

    /** {@link EntityOptions} 的可变构造器 */
    public static final class Builder {
        private String packageName = "";
        private List<String> tablePrefixes = new ArrayList<>();
        private String classSuffix = "";
        private Style style = Style.LOMBOK;
        private boolean lombokBuilder;
        private boolean lombokNoArgsConstructor;
        private boolean lombokAllArgsConstructor;
        private boolean plainToString = true;
        private Annotations annotations = Annotations.NONE;
        private JpaNamespace jpaNamespace = JpaNamespace.JAKARTA;
        private boolean jpaAlwaysColumn;
        private boolean serializable;
        private boolean javadoc = true;
        private boolean legacyDate;
        private boolean unsignedBigintAsBigInteger;
        private boolean mybatisXml;

        private Builder() {
        }

        public Builder packageName(String value) {
            this.packageName = value;
            return this;
        }

        public Builder tablePrefixes(List<String> value) {
            this.tablePrefixes = value == null ? new ArrayList<>() : new ArrayList<>(value);
            return this;
        }

        public Builder tablePrefixes(String commaSeparated) {
            return tablePrefixes(parsePrefixes(commaSeparated));
        }

        public Builder classSuffix(String value) {
            this.classSuffix = value;
            return this;
        }

        public Builder style(Style value) {
            this.style = value;
            return this;
        }

        public Builder lombokBuilder(boolean value) {
            this.lombokBuilder = value;
            return this;
        }

        public Builder lombokNoArgsConstructor(boolean value) {
            this.lombokNoArgsConstructor = value;
            return this;
        }

        public Builder lombokAllArgsConstructor(boolean value) {
            this.lombokAllArgsConstructor = value;
            return this;
        }

        public Builder plainToString(boolean value) {
            this.plainToString = value;
            return this;
        }

        public Builder annotations(Annotations value) {
            this.annotations = value;
            return this;
        }

        public Builder jpaNamespace(JpaNamespace value) {
            this.jpaNamespace = value;
            return this;
        }

        public Builder jpaAlwaysColumn(boolean value) {
            this.jpaAlwaysColumn = value;
            return this;
        }

        public Builder serializable(boolean value) {
            this.serializable = value;
            return this;
        }

        public Builder javadoc(boolean value) {
            this.javadoc = value;
            return this;
        }

        public Builder legacyDate(boolean value) {
            this.legacyDate = value;
            return this;
        }

        public Builder unsignedBigintAsBigInteger(boolean value) {
            this.unsignedBigintAsBigInteger = value;
            return this;
        }

        public Builder mybatisXml(boolean value) {
            this.mybatisXml = value;
            return this;
        }

        public EntityOptions build() {
            return new EntityOptions(packageName, tablePrefixes, classSuffix, style, lombokBuilder,
                    lombokNoArgsConstructor, lombokAllArgsConstructor, plainToString, annotations,
                    jpaNamespace, jpaAlwaysColumn, serializable, javadoc, legacyDate,
                    unsignedBigintAsBigInteger, mybatisXml);
        }
    }
}
