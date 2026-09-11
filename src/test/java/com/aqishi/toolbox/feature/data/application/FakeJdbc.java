package com.aqishi.toolbox.feature.data.application;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 JDK 动态代理的最小 JDBC 假实现：只支持元数据测试用到的方法，
 * 其余调用一律抛 {@link UnsupportedOperationException} 以暴露意外路径。
 */
final class FakeJdbc {

    /** 一行结果集：列标签 → 值；索引 1 取第一个值。 */
    record Row(Map<String, Object> columns) {
        static Row of(String label, Object value) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(label, value);
            return Row.of(map);
        }

        static Row of(Map<String, Object> map) {
            return new Row(map);
        }

        Object byLabel(String label) {
            return columns.get(label);
        }

        Object byIndex(int index) {
            return new ArrayList<>(columns.values()).get(index - 1);
        }
    }

    static final class Db {
        List<Row> catalogs = new ArrayList<>();
        List<Row> schemas = new ArrayList<>();
        List<Row> tables = new ArrayList<>();
        List<Row> functions = new ArrayList<>();
        List<Row> columns = new ArrayList<>();
        /** SQL 前缀 → 行；不匹配任何前缀的查询返回空结果。 */
        Map<String, List<Row>> queryResults = new LinkedHashMap<>();
        /** 命中这些前缀的查询抛 SQLException，用于触发回退链。 */
        List<String> failingQueries = new ArrayList<>();
        boolean failFunctions;
        boolean failGetCatalog;
        boolean failGetSchema;
        boolean failSetSchema;
        String currentCatalog;
        String currentSchema;
        /** 通过 Statement.execute 执行的 SQL，按执行顺序记录。 */
        List<String> executedSql = new ArrayList<>();
        String setSchemaArg;
        String setCatalogArg;

        Connection connection() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "getMetaData" -> metaData();
                case "createStatement" -> statement();
                case "getCatalog" -> {
                    if (failGetCatalog) {
                        throw new SQLException("getCatalog not supported");
                    }
                    yield currentCatalog;
                }
                case "getSchema" -> {
                    if (failGetSchema) {
                        throw new SQLException("getSchema not supported");
                    }
                    yield currentSchema;
                }
                case "setSchema" -> {
                    if (failSetSchema) {
                        throw new SQLException("setSchema not supported");
                    }
                    setSchemaArg = (String) args[0];
                    yield null;
                }
                case "setCatalog" -> {
                    setCatalogArg = (String) args[0];
                    yield null;
                }
                case "close" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            };
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, handler);
        }

        private DatabaseMetaData metaData() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "getCatalogs" -> resultSet(catalogs);
                case "getSchemas" -> resultSet(schemas);
                case "getTables" -> resultSet(tables);
                case "getFunctions" -> {
                    if (failFunctions) {
                        throw new SQLException("getFunctions not supported");
                    }
                    yield resultSet(functions);
                }
                case "getColumns" -> resultSet(columns);
                default -> throw new UnsupportedOperationException("DatabaseMetaData." + method.getName());
            };
            return (DatabaseMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{DatabaseMetaData.class}, handler);
        }

        private Statement statement() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "executeQuery" -> {
                    String sql = (String) args[0];
                    for (String failing : failingQueries) {
                        if (sql.startsWith(failing)) {
                            throw new SQLException("query failed: " + failing);
                        }
                    }
                    for (Map.Entry<String, List<Row>> entry : queryResults.entrySet()) {
                        if (sql.startsWith(entry.getKey())) {
                            yield resultSet(entry.getValue());
                        }
                    }
                    yield resultSet(List.of());
                }
                case "execute" -> {
                    executedSql.add((String) args[0]);
                    yield true;
                }
                case "close" -> null;
                default -> throw new UnsupportedOperationException("Statement." + method.getName());
            };
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Statement.class}, handler);
        }

        private static ResultSet resultSet(List<Row> rows) {
            Iterator<Row> iterator = rows.iterator();
            boolean[] closed = {false};
            InvocationHandler handler = new InvocationHandler() {
                private Row current;

                @Override
                public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                    return switch (method.getName()) {
                        case "next" -> {
                            if (iterator.hasNext()) {
                                current = iterator.next();
                                yield true;
                            }
                            yield false;
                        }
                        case "getString" -> {
                            Object value = args[0] instanceof Integer index
                                    ? current.byIndex(index) : current.byLabel((String) args[0]);
                            yield value == null ? null : value.toString();
                        }
                        case "close" -> {
                            closed[0] = true;
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException("ResultSet." + method.getName());
                    };
                }
            };
            return (ResultSet) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, handler);
        }
    }

    private FakeJdbc() {
    }
}
