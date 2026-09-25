package com.aqishi.toolbox.feature.generation.domain;

import java.util.List;

/**
 * DDL 解析结果：成功解析的表 + 解析过程中的告警。
 *
 * <p>解析是「尽量多拿」的：某条建表语句写坏了只会产生一条告警并跳过这张表，
 * 其余语句照常解析——粘贴一整份库导出时，不能因为一处方言语法不认识就全盘失败。</p>
 *
 * @param tables   成功解析的表，按出现顺序
 * @param warnings 告警，按出现顺序
 */
public record DdlParseResult(List<TableDef> tables, List<Warning> warnings) {

    public DdlParseResult {
        tables = tables == null ? List.of() : List.copyOf(tables);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * 一条解析告警。
     *
     * <p>{@code message} 是英文的技术描述（与编译器报错同类），界面层负责加上本地化的前缀；
     * 领域层不产出任何面向用户的中文文案。</p>
     *
     * @param line    1 起始的行号
     * @param column  1 起始的列号
     * @param message 英文描述
     */
    public record Warning(int line, int column, String message) {

        @Override
        public String toString() {
            return line + ":" + column + " " + message;
        }
    }
}
