package com.aqishi.toolbox.feature.generation.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 从建表语句中解析出的一张表。
 *
 * @param schema     模式/库名；{@code db.t_user} 取 {@code db}，三段式 {@code db.dbo.t} 取中间的
 *                   {@code dbo}；未限定时为 null
 * @param name       表名
 * @param columns    列，保持建表语句中的顺序
 * @param primaryKey 主键列名，按主键声明顺序；无主键时为空列表
 * @param comment    表注释；未声明时为 null
 */
public record TableDef(
        String schema,
        String name,
        List<ColumnDef> columns,
        List<String> primaryKey,
        String comment) {

    public TableDef {
        Objects.requireNonNull(name, "name");
        columns = columns == null ? List.of() : List.copyOf(columns);
        primaryKey = primaryKey == null ? List.of() : List.copyOf(primaryKey);
    }

    /** 按列名查找（不区分大小写） */
    public Optional<ColumnDef> column(String columnName) {
        for (ColumnDef column : columns) {
            if (column.name().equalsIgnoreCase(columnName)) {
                return Optional.of(column);
            }
        }
        return Optional.empty();
    }

    /** 是否为多列联合主键 */
    public boolean hasCompositeKey() {
        return primaryKey.size() > 1;
    }

    /** 是否带有非空注释 */
    public boolean hasComment() {
        return comment != null && !comment.isBlank();
    }
}
