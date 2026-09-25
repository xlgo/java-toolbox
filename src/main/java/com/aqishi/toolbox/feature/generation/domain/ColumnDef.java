package com.aqishi.toolbox.feature.generation.domain;

import java.util.List;
import java.util.Objects;

/**
 * 从建表语句中解析出的一列。
 *
 * <p>{@code typeName} 是规范化后的基础类型：小写、多个单词之间一个空格、去掉参数与修饰，
 * 例如 {@code DECIMAL(10,2) UNSIGNED} 记为 {@code decimal}，{@code CHARACTER VARYING(20)}
 * 记为 {@code character varying}，{@code TIMESTAMP(3) WITH TIME ZONE} 记为
 * {@code timestamp with time zone}。原样写法保存在 {@code declaredType} 中，仅供展示。</p>
 *
 * @param name            列名（已去掉反引号、双引号或方括号）
 * @param typeName        规范化后的基础类型名
 * @param declaredType    源文本中的类型写法，例如 {@code DECIMAL(10,2) UNSIGNED}
 * @param length          第一个数值参数：字符类型是长度，数值类型是精度；没有时为 null
 * @param scale           第二个数值参数（小数位数）；没有时为 null
 * @param enumValues      ENUM/SET 的候选值，其他类型为空列表
 * @param unsigned        是否带 UNSIGNED
 * @param arrayDimensions 数组维数，{@code text[]} 为 1，非数组为 0
 * @param nullable        是否允许为空（主键与 SERIAL 列视为不可空）
 * @param primaryKey      是否为主键（列级或表级 PRIMARY KEY 都会反映到这里）
 * @param autoIncrement   是否自增（AUTO_INCREMENT、SERIAL、IDENTITY、nextval 默认值）
 * @param unique          是否声明了列级 UNIQUE
 * @param defaultValue    DEFAULT 表达式的原文；未声明时为 null
 * @param comment         列注释；未声明时为 null
 */
public record ColumnDef(
        String name,
        String typeName,
        String declaredType,
        Integer length,
        Integer scale,
        List<String> enumValues,
        boolean unsigned,
        int arrayDimensions,
        boolean nullable,
        boolean primaryKey,
        boolean autoIncrement,
        boolean unique,
        String defaultValue,
        String comment) {

    public ColumnDef {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(typeName, "typeName");
        declaredType = declaredType == null ? typeName : declaredType;
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    }

    /** 是否为数组列 */
    public boolean isArray() {
        return arrayDimensions > 0;
    }

    /** 是否带有非空注释 */
    public boolean hasComment() {
        return comment != null && !comment.isBlank();
    }

    /** 返回替换了注释的副本 */
    public ColumnDef withComment(String newComment) {
        return new ColumnDef(name, typeName, declaredType, length, scale, enumValues, unsigned,
                arrayDimensions, nullable, primaryKey, autoIncrement, unique, defaultValue, newComment);
    }
}
