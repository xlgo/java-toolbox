package com.aqishi.toolbox.feature.data.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 一个库/模式下的数据库对象清单：表、视图、函数三类，各自按字典序排序。
 */
public record DatabaseObjects(List<String> tables, List<String> views, List<String> functions) {

    /** 一行对象元数据：JDBC 的 TABLE_TYPE 与名称。 */
    public record Row(String type, String name) {
    }

    /** 按 TABLE_TYPE 拆分，{@code VIEW} 进视图列表，其余进表列表。 */
    public static DatabaseObjects fromRows(Collection<Row> rows,
                                           Collection<String> functionNames) {
        List<String> tables = new ArrayList<>();
        List<String> views = new ArrayList<>();
        for (Row row : rows) {
            if ("VIEW".equals(row.type())) {
                views.add(row.name());
            } else {
                tables.add(row.name());
            }
        }
        List<String> functions = new ArrayList<>(functionNames);
        Collections.sort(tables);
        Collections.sort(views);
        Collections.sort(functions);
        return new DatabaseObjects(tables, views, functions);
    }

    /** 排序后去重（保持排序序）。 */
    public static List<String> sortedUnique(Collection<String> names) {
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        return new ArrayList<>(new LinkedHashSet<>(sorted));
    }
}
