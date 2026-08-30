package com.aqishi.toolbox.feature.data.application;

import com.aqishi.toolbox.feature.data.domain.QueryResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlExecutionServiceTest {

    // ===== 极简 JDBC 动态代理桩 =====

    private static Object proxy(Class<?> iface, Map<String, Object> handlers) {
        return Proxy.newProxyInstance(SqlExecutionServiceTest.class.getClassLoader(),
                new Class<?>[]{iface}, new Handler(handlers));
    }

    private static final class Handler implements InvocationHandler {
        private final Map<String, Object> handlers;

        Handler(Map<String, Object> handlers) {
            this.handlers = handlers;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("close".equals(method.getName()) || "hashCode".equals(method.getName())) {
                return method.getName().equals("hashCode") ? System.identityHashCode(proxy) : null;
            }
            if ("equals".equals(method.getName())) {
                return proxy == args[0];
            }
            Object result = handlers.get(method.getName());
            return result instanceof Invoke ? ((Invoke) result).apply(args) : result;
        }
    }

    private interface Invoke {
        Object apply(Object[] args);
    }

    /** 构造一个按行返回数据的 ResultSet 桩：next() 推进游标，getObject(i) 读取当前行第 i 列 */
    private static ResultSet fakeResultSet(List<String> labels, List<Object[]> rows) {
        ResultSetMetaData meta = (ResultSetMetaData) proxy(ResultSetMetaData.class, Map.of(
                "getColumnCount", labels.size(),
                "getColumnLabel", (Invoke) args -> labels.get((Integer) args[0] - 1)));
        int[] cursorRef = {0};
        Map<String, Object> handlers = new HashMap<>();
        handlers.put("getMetaData", meta);
        handlers.put("next", (Invoke) args -> {
            if (cursorRef[0] < rows.size()) {
                cursorRef[0]++;
                return true;
            }
            return false;
        });
        handlers.put("getObject", (Invoke) args -> rows.get(cursorRef[0] - 1)[(Integer) args[0] - 1]);
        return (ResultSet) proxy(ResultSet.class, handlers);
    }

    private static Connection fakeConnection(boolean closed, Statement statement) {
        Map<String, Object> handlers = new HashMap<>();
        handlers.put("isClosed", closed);
        handlers.put("createStatement", statement);
        return (Connection) proxy(Connection.class, handlers);
    }

    private static Statement fakeStatement(Map<String, Object> extra) {
        Map<String, Object> handlers = new HashMap<>(extra);
        handlers.putIfAbsent("close", null);
        return (Statement) proxy(Statement.class, handlers);
    }

    // ===== 构造参数校验 =====

    @Test
    void rejectsNonPositiveMaxRows() {
        assertThrows(IllegalArgumentException.class, () -> new SqlExecutionService(0));
        assertThrows(IllegalArgumentException.class, () -> new SqlExecutionService(-1));
    }

    @Test
    void rejectsClosedOrNullConnection() {
        SqlExecutionService service = new SqlExecutionService();
        assertThrows(IllegalStateException.class, () -> service.execute(null, "SELECT 1"));
        assertThrows(IllegalStateException.class,
                () -> service.execute(fakeConnection(true, null), "SELECT 1"));
    }

    @Test
    void rejectsBlankSql() {
        SqlExecutionService service = new SqlExecutionService();
        Connection connection = fakeConnection(false, null);
        assertThrows(IllegalArgumentException.class, () -> service.execute(connection, null));
        assertThrows(IllegalArgumentException.class, () -> service.execute(connection, "   "));
    }

    // ===== 更新语句路径 =====

    @Test
    void updateStatementProducesUpdateResult() throws Exception {
        Statement statement = fakeStatement(Map.of(
                "execute", false,
                "getUpdateCount", 3));
        Connection connection = fakeConnection(false, statement);

        QueryResult result = new SqlExecutionService().execute(connection, "DELETE FROM t WHERE id=1");
        assertTrue(result.isUpdate());
        assertEquals(3, result.getUpdateCount());
        assertEquals(0, result.getRows().size());
        assertNull(result.getWarning());
    }

    // ===== 查询语句路径 =====

    @Test
    void queryStatementReadsAllRowsAndColumnLabels() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{1, "alice"});
        rows.add(new Object[]{2, null});
        ResultSet resultSet = fakeResultSet(List.of("id", "name"), rows);
        Statement statement = fakeStatement(Map.of(
                "execute", true,
                "getResultSet", resultSet));
        Connection connection = fakeConnection(false, statement);

        QueryResult result = new SqlExecutionService().execute(connection, "SELECT id, name FROM t");
        assertFalse(result.isUpdate());
        assertEquals(List.of("id", "name"), result.getColumnNames());
        assertEquals(2, result.getRows().size());
        assertEquals("1", result.getRows().get(0).get(0));
        assertEquals("alice", result.getRows().get(0).get(1));
        assertNull(result.getRows().get(1).get(1));
        assertNull(result.getWarning());
    }

    @Test
    void truncatesBeyondMaxRowsWithWarning() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            rows.add(new Object[]{i});
        }
        ResultSet resultSet = fakeResultSet(List.of("id"), rows);
        Statement statement = fakeStatement(Map.of(
                "execute", true,
                "getResultSet", resultSet));
        Connection connection = fakeConnection(false, statement);

        QueryResult result = new SqlExecutionService(2).execute(connection, "SELECT id FROM t");
        assertEquals(2, result.getRows().size());
        assertNotNull(result.getWarning());
        assertTrue(result.getWarning().contains("2"));
    }

    @Test
    void binaryValuesAreRenderedAsPlaceholders() throws Exception {
        byte[] blob = new byte[42];
        ResultSet resultSet = fakeResultSet(List.of("data"), Collections.singletonList(new Object[]{blob}));
        Statement statement = fakeStatement(Map.of(
                "execute", true,
                "getResultSet", resultSet));
        Connection connection = fakeConnection(false, statement);

        QueryResult result = new SqlExecutionService().execute(connection, "SELECT data FROM t");
        assertEquals("[Binary: 42 bytes]", result.getRows().get(0).get(0));
    }
}
