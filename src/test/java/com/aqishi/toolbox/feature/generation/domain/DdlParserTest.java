package com.aqishi.toolbox.feature.generation.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DdlParserTest {

    private final DdlParser parser = new DdlParser();

    private static ColumnDef col(TableDef table, String name) {
        return table.column(name).orElseThrow(() -> new AssertionError("missing column " + name));
    }

    @Test
    void parsesMysqlDumpStyleTable() {
        String ddl = "-- MySQL dump 10.13\n"
                + "/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;\n"
                + "DROP TABLE IF EXISTS `t_user`;\n"
                + "CREATE TABLE `t_user` (\n"
                + "  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',\n"
                + "  `user_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '用户名',\n"
                + "  `age` int(11) DEFAULT NULL,\n"
                + "  `is_deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否删除',\n"
                + "  `balance` decimal(10,2) NOT NULL DEFAULT '0.00',\n"
                + "  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
                + "  `updated_at` timestamp(3) NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),\n"
                + "  PRIMARY KEY (`id`),\n"
                + "  UNIQUE KEY `uk_name` (`user_name`),\n"
                + "  KEY `idx_created` (`created_at`) USING BTREE,\n"
                + "  CONSTRAINT `fk_x` FOREIGN KEY (`age`) REFERENCES `t_age` (`id`) ON DELETE SET NULL\n"
                + ") ENGINE=InnoDB AUTO_INCREMENT=42 DEFAULT CHARSET=utf8mb4 COMMENT='用户表';\n"
                + "LOCK TABLES `t_user` WRITE;\n"
                + "INSERT INTO `t_user` VALUES (1,'a(b),c',3,0,1.00,NOW(),NOW());\n"
                + "UNLOCK TABLES;\n";

        DdlParseResult result = parser.parse(ddl);

        assertEquals(List.of(), result.warnings());
        assertEquals(1, result.tables().size());
        TableDef table = result.tables().get(0);
        assertEquals("t_user", table.name());
        assertNull(table.schema());
        assertEquals("用户表", table.comment());
        assertEquals(List.of("id", "user_name", "age", "is_deleted", "balance", "created_at", "updated_at"),
                table.columns().stream().map(ColumnDef::name).toList());
        assertEquals(List.of("id"), table.primaryKey());

        ColumnDef id = col(table, "id");
        assertEquals("bigint", id.typeName());
        assertTrue(id.unsigned());
        assertTrue(id.autoIncrement());
        assertTrue(id.primaryKey());
        assertFalse(id.nullable());
        assertEquals("主键", id.comment());
        assertEquals("bigint(20) unsigned", id.declaredType());

        ColumnDef name = col(table, "user_name");
        assertEquals("varchar", name.typeName());
        assertEquals(64, name.length());
        assertFalse(name.nullable());
        assertEquals("''", name.defaultValue());
        assertEquals("用户名", name.comment());

        assertTrue(col(table, "age").nullable());
        assertEquals("NULL", col(table, "age").defaultValue());
        assertEquals(1, col(table, "is_deleted").length());
        assertEquals(10, col(table, "balance").length());
        assertEquals(2, col(table, "balance").scale());
        assertEquals("CURRENT_TIMESTAMP(3)", col(table, "updated_at").defaultValue());
        assertTrue(col(table, "updated_at").nullable());
    }

    @Test
    void parsesPostgresWithCommentOnStatements() {
        String ddl = "COMMENT ON TABLE public.orders IS '订单';\n" // 早于建表语句出现也要生效
                + "CREATE TABLE IF NOT EXISTS public.orders (\n"
                + "    id BIGSERIAL PRIMARY KEY,\n"
                + "    code CHARACTER VARYING(20) NOT NULL,\n"
                + "    tags text[] DEFAULT '{}'::text[],\n"
                + "    matrix integer[][],\n"
                + "    paid_at timestamptz,\n"
                + "    shipped_at TIMESTAMP(3) WITH TIME ZONE,\n"
                + "    local_at timestamp without time zone,\n"
                + "    amount double precision,\n"
                + "    status varchar(16) DEFAULT 'new'::character varying NOT NULL,\n"
                + "    meta jsonb,\n"
                + "    ref uuid NOT NULL\n"
                + ");\n"
                + "COMMENT ON COLUMN public.orders.code IS '订单号';\n"
                + "COMMENT ON COLUMN orders.amount IS 'it''s money';\n"
                + "COMMENT ON INDEX idx_x IS 'ignored';\n";

        DdlParseResult result = parser.parse(ddl);

        assertEquals(List.of(), result.warnings());
        TableDef table = result.tables().get(0);
        assertEquals("public", table.schema());
        assertEquals("orders", table.name());
        assertEquals("订单", table.comment());

        ColumnDef id = col(table, "id");
        assertEquals("bigserial", id.typeName());
        assertTrue(id.autoIncrement());
        assertTrue(id.primaryKey());
        assertEquals(List.of("id"), table.primaryKey());

        assertEquals("character varying", col(table, "code").typeName());
        assertEquals(20, col(table, "code").length());
        assertEquals("订单号", col(table, "code").comment());
        assertEquals(1, col(table, "tags").arrayDimensions());
        assertEquals("text", col(table, "tags").typeName());
        assertEquals("'{}'::text[]", col(table, "tags").defaultValue());
        assertEquals(2, col(table, "matrix").arrayDimensions());
        assertEquals("timestamptz", col(table, "paid_at").typeName());
        assertEquals("timestamp with time zone", col(table, "shipped_at").typeName());
        assertEquals(3, col(table, "shipped_at").length());
        assertEquals("timestamp without time zone", col(table, "local_at").typeName());
        assertEquals("double precision", col(table, "amount").typeName());
        assertEquals("it's money", col(table, "amount").comment());
        assertFalse(col(table, "status").nullable());
        assertEquals("'new'::character varying", col(table, "status").defaultValue());
        assertFalse(col(table, "ref").nullable());
    }

    @Test
    void parsesPgDumpWithAlterTablePrimaryKeyAndNextval() {
        String ddl = "SET statement_timeout = 0;\n"
                + "CREATE TABLE public.items (\n"
                + "    id integer NOT NULL,\n"
                + "    name text\n"
                + ");\n"
                + "ALTER TABLE ONLY public.items ALTER COLUMN id SET DEFAULT nextval('public.items_id_seq'::regclass);\n"
                + "ALTER TABLE ONLY public.items\n"
                + "    ADD CONSTRAINT items_pkey PRIMARY KEY (id);\n";

        TableDef table = parser.parse(ddl).tables().get(0);

        assertEquals(List.of("id"), table.primaryKey());
        assertTrue(col(table, "id").primaryKey());
        assertTrue(col(table, "id").autoIncrement());
    }

    @Test
    void parsesOracleTypes() {
        String ddl = "CREATE TABLE \"HR\".\"EMPLOYEES\" (\n"
                + "  \"EMPLOYEE_ID\" NUMBER(6,0) NOT NULL ENABLE,\n"
                + "  \"FIRST_NAME\" VARCHAR2(20 BYTE),\n"
                + "  \"SALARY\" NUMBER(8,2),\n"
                + "  \"RATIO\" NUMBER,\n"
                + "  \"BIG\" NUMBER(*,0),\n"
                + "  \"RESUME\" CLOB,\n"
                + "  \"HIRE_DATE\" DATE DEFAULT SYSDATE NOT NULL,\n"
                + "  \"STAMP\" TIMESTAMP (6) WITH LOCAL TIME ZONE,\n"
                + "  CONSTRAINT \"EMP_PK\" PRIMARY KEY (\"EMPLOYEE_ID\") USING INDEX ENABLE\n"
                + ") SEGMENT CREATION IMMEDIATE TABLESPACE \"USERS\";\n";

        DdlParseResult result = parser.parse(ddl);

        assertEquals(List.of(), result.warnings());
        TableDef table = result.tables().get(0);
        assertEquals("HR", table.schema());
        assertEquals("EMPLOYEES", table.name());
        assertEquals(List.of("EMPLOYEE_ID"), table.primaryKey());
        assertEquals("number", col(table, "EMPLOYEE_ID").typeName());
        assertEquals(6, col(table, "EMPLOYEE_ID").length());
        assertEquals(0, col(table, "EMPLOYEE_ID").scale());
        assertEquals("varchar2", col(table, "FIRST_NAME").typeName());
        assertEquals(20, col(table, "FIRST_NAME").length());
        assertNull(col(table, "RATIO").length());
        assertNull(col(table, "BIG").length());
        assertEquals(0, col(table, "BIG").scale());
        assertEquals("SYSDATE", col(table, "HIRE_DATE").defaultValue());
        assertFalse(col(table, "HIRE_DATE").nullable());
        assertEquals("timestamp with local time zone", col(table, "STAMP").typeName());
    }

    @Test
    void parsesSqlServerBracketsAndIdentity() {
        String ddl = "CREATE TABLE [dbo].[Orders](\n"
                + "\t[OrderID] [int] IDENTITY(1,1) NOT NULL,\n"
                + "\t[Order Name] [nvarchar](max) NULL,\n"
                + "\t[Flag] [bit] NOT NULL,\n"
                + " CONSTRAINT [PK_Orders] PRIMARY KEY CLUSTERED ([OrderID] ASC)\n"
                + ") ON [PRIMARY]\n"
                + "GO\n"
                + "CREATE TABLE [dbo].[Other]([go] int)\n"
                + "GO\n";

        DdlParseResult result = parser.parse(ddl);

        assertEquals(List.of(), result.warnings());
        assertEquals(2, result.tables().size());
        TableDef table = result.tables().get(0);
        assertEquals("dbo", table.schema());
        assertEquals("Orders", table.name());
        assertTrue(col(table, "OrderID").autoIncrement());
        assertEquals(List.of("OrderID"), table.primaryKey());
        assertEquals("nvarchar", col(table, "Order Name").typeName());
        assertNull(col(table, "Order Name").length());
        assertEquals("bit", col(table, "Flag").typeName());
        assertEquals("go", result.tables().get(1).columns().get(0).name());
    }

    @Test
    void handlesCompositePrimaryKey() {
        String ddl = "CREATE TABLE user_role (\n"
                + "  user_id BIGINT NOT NULL,\n"
                + "  role_id INT NOT NULL,\n"
                + "  granted_at DATETIME,\n"
                + "  PRIMARY KEY (user_id, role_id)\n"
                + ");";

        TableDef table = parser.parse(ddl).tables().get(0);

        assertEquals(List.of("user_id", "role_id"), table.primaryKey());
        assertTrue(table.hasCompositeKey());
        assertTrue(col(table, "role_id").primaryKey());
        assertFalse(col(table, "granted_at").primaryKey());
    }

    @Test
    void keepsCommasAndParensInsideLiterals() {
        String ddl = "CREATE TABLE t_tricky (\n"
                + "  kind ENUM('a,b', 'c)', 'it''s', 'x\\'y') NOT NULL DEFAULT 'a,b',\n"
                + "  flags SET('r','w') DEFAULT 'r,w',\n"
                + "  note VARCHAR(20) DEFAULT 'x(y), z' COMMENT '注释, 带逗号 (和括号)',\n"
                + "  bits BIT(8) DEFAULT b'0',\n"
                + "  name NVARCHAR(10) DEFAULT N'abc',\n"
                + "  price DECIMAL(10,2) DEFAULT -1.5,\n"
                + "  expr INT DEFAULT (1 + 2) NOT NULL\n"
                + ")";

        DdlParseResult result = parser.parse(ddl);

        assertEquals(List.of(), result.warnings());
        TableDef table = result.tables().get(0);
        assertEquals(7, table.columns().size());
        ColumnDef kind = col(table, "kind");
        assertEquals("enum", kind.typeName());
        assertEquals(List.of("a,b", "c)", "it's", "x'y"), kind.enumValues());
        assertEquals("'a,b'", kind.defaultValue());
        assertFalse(kind.nullable());
        assertEquals("'x(y), z'", col(table, "note").defaultValue());
        assertEquals("注释, 带逗号 (和括号)", col(table, "note").comment());
        assertEquals("b'0'", col(table, "bits").defaultValue());
        assertEquals("N'abc'", col(table, "name").defaultValue());
        assertEquals("-1.5", col(table, "price").defaultValue());
        assertEquals("(1 + 2)", col(table, "expr").defaultValue());
        assertFalse(col(table, "expr").nullable());
    }

    @Test
    void ignoresCommentsInOddPlaces() {
        String ddl = "/* header */ CREATE /* x */ TABLE -- trailing\n"
                + "  demo # mysql comment\n"
                + "  ( -- open\n"
                + "  id /* inline */ INT /* after type */ PRIMARY KEY, -- comment, with comma\n"
                + "  # whole line comment (with parens\n"
                + "  label VARCHAR(10) /* ) */ COMMENT 'label -- not a comment'\n"
                + "  /* before close */ ) /* after */ ;";

        DdlParseResult result = parser.parse(ddl);

        assertEquals(List.of(), result.warnings());
        TableDef table = result.tables().get(0);
        assertEquals("demo", table.name());
        assertEquals(2, table.columns().size());
        assertEquals("label -- not a comment", col(table, "label").comment());
        assertEquals(List.of("id"), table.primaryKey());
    }

    @Test
    void brokenStatementYieldsWarningWhileOthersParse() {
        String ddl = "CREATE TABLE good_one (id INT PRIMARY KEY);\n"
                + "CREATE TABLE broken (\n"
                + "  id INT,\n"
                + "  name VARCHAR(20)\n"
                + ";\n"
                + "CREATE TABLE no_type (id);\n"
                + "CREATE TABLE copy_of AS SELECT * FROM good_one;\n"
                + "CREATE TABLE good_two (code CHAR(2))";

        DdlParseResult result = parser.parse(ddl);

        assertEquals(List.of("good_one", "good_two"),
                result.tables().stream().map(TableDef::name).toList());
        assertEquals(3, result.warnings().size());
        DdlParseResult.Warning unclosed = result.warnings().get(0);
        assertEquals(2, unclosed.line());
        assertEquals(21, unclosed.column());
        assertTrue(unclosed.message().contains("broken"), unclosed.message());
        assertTrue(result.warnings().get(1).message().contains("no data type"));
        assertEquals(6, result.warnings().get(1).line());
        assertTrue(result.warnings().get(2).message().contains("AS"));
    }

    @Test
    void missingSemicolonDoesNotSwallowNextTable() {
        String ddl = "CREATE TABLE a (id INT)\nCREATE TABLE b (id INT)\n";

        assertEquals(2, parser.parse(ddl).tables().size());
    }

    @Test
    void reportsUnterminatedStringWithPosition() {
        DdlParseResult result = parser.parse("CREATE TABLE t (\n  id INT COMMENT 'oops\n)");

        assertTrue(result.tables().isEmpty());
        assertFalse(result.warnings().isEmpty());
        DdlParseResult.Warning first = result.warnings().get(0);
        assertEquals(2, first.line());
        assertEquals(18, first.column());
        assertTrue(first.message().startsWith("Unterminated string"));
    }

    @Test
    void parsesMultipleTablesWithSchemaAndTemporaryModifiers() {
        String ddl = "USE shop;\n"
                + "CREATE TEMPORARY TABLE IF NOT EXISTS shop.tmp_a (x INT);\n"
                + "CREATE GLOBAL TEMPORARY TABLE b (y INT) ON COMMIT DELETE ROWS;\n"
                + "CREATE INDEX idx ON b (y);\n"
                + "CREATE VIEW v AS SELECT 1;\n"
                + "CREATE UNLOGGED TABLE c (z INT);";

        DdlParseResult result = parser.parse(ddl);

        assertEquals(List.of(), result.warnings());
        assertEquals(List.of("tmp_a", "b", "c"), result.tables().stream().map(TableDef::name).toList());
        assertEquals("shop", result.tables().get(0).schema());
    }

    @Test
    void parsesGeneratedIdentityAndMiscModifiers() {
        String ddl = "CREATE TABLE g (\n"
                + "  id BIGINT GENERATED BY DEFAULT AS IDENTITY (START WITH 1 INCREMENT BY 1),\n"
                + "  id2 INT GENERATED ALWAYS AS IDENTITY,\n"
                + "  total INT GENERATED ALWAYS AS (id2 * 2) STORED,\n"
                + "  key VARCHAR(10),\n"
                + "  score INT UNSIGNED ZEROFILL CHECK (score > 0),\n"
                + "  owner_id INT REFERENCES users (id) ON DELETE CASCADE NOT NULL,\n"
                + "  uid INT UNIQUE,\n"
                + "  PRIMARY KEY (id)\n"
                + ")";

        DdlParseResult result = parser.parse(ddl);

        assertEquals(List.of(), result.warnings());
        TableDef table = result.tables().get(0);
        assertTrue(col(table, "id").autoIncrement());
        assertTrue(col(table, "id2").autoIncrement());
        assertFalse(col(table, "total").autoIncrement());
        assertEquals("varchar", col(table, "key").typeName());
        assertTrue(col(table, "score").unsigned());
        assertFalse(col(table, "owner_id").nullable());
        assertTrue(col(table, "uid").unique());
        assertEquals(7, table.columns().size());
    }

    @Test
    void laterDuplicateDefinitionWins() {
        DdlParseResult result = parser.parse("CREATE TABLE t (a INT); CREATE TABLE t (b INT);");

        assertEquals(1, result.tables().size());
        assertEquals("b", result.tables().get(0).columns().get(0).name());
        assertEquals(1, result.warnings().size());
    }

    @Test
    void emptyAndNullInputProduceNothing() {
        assertTrue(parser.parse(null).tables().isEmpty());
        assertTrue(parser.parse("  -- nothing here\n").warnings().isEmpty());
    }
}
