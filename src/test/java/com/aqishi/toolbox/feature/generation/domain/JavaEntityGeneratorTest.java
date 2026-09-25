package com.aqishi.toolbox.feature.generation.domain;

import com.aqishi.toolbox.feature.generation.domain.EntityOptions.Annotations;
import com.aqishi.toolbox.feature.generation.domain.EntityOptions.JpaNamespace;
import com.aqishi.toolbox.feature.generation.domain.EntityOptions.Style;
import com.aqishi.toolbox.feature.generation.domain.JavaEntityGenerator.FileKind;
import com.aqishi.toolbox.feature.generation.domain.JavaEntityGenerator.GeneratedFile;
import com.aqishi.toolbox.feature.generation.domain.JavaEntityGenerator.Result;
import com.aqishi.toolbox.feature.generation.domain.JavaEntityGenerator.WarningCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaEntityGeneratorTest {

    private static final String MYSQL = "CREATE TABLE `t_user` (\n"
            + "  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',\n"
            + "  `user_name` varchar(64) NOT NULL COMMENT '用户名',\n"
            + "  `email` varchar(128) UNIQUE,\n"
            + "  `is_deleted` tinyint(1) NOT NULL DEFAULT '0',\n"
            + "  `balance` decimal(12,2),\n"
            + "  `birthday` date,\n"
            + "  `login_time` time,\n"
            + "  `created_at` datetime NOT NULL,\n"
            + "  `avatar` longblob,\n"
            + "  `bio` longtext,\n"
            + "  `tags` json,\n"
            + "  `userID` int,\n"
            + "  PRIMARY KEY (`id`)\n"
            + ") COMMENT='用户表';";

    private final DdlParser parser = new DdlParser();
    private final JavaEntityGenerator generator = new JavaEntityGenerator();

    private Result generate(String ddl, EntityOptions options) {
        DdlParseResult parsed = parser.parse(ddl);
        assertEquals(List.of(), parsed.warnings(), "fixture DDL should parse cleanly");
        return generator.generate(parsed.tables(), options);
    }

    private static String javaOf(Result result, int index) {
        return result.files().stream().filter(f -> f.kind() == FileKind.JAVA).toList().get(index).content();
    }

    private static List<WarningCode> codes(Result result) {
        return result.warnings().stream().map(JavaEntityGenerator.Warning::code).toList();
    }

    // ==========================================
    // 命名
    // ==========================================

    @Test
    void derivesClassNamesWithPrefixStrippingAndSuffix() {
        EntityOptions options = EntityOptions.builder().tablePrefixes("t_, sys_").classSuffix("DO").build();

        assertEquals("UserInfoDO", JavaEntityGenerator.className("t_user_info", options));
        assertEquals("UserDO", JavaEntityGenerator.className("SYS_USER", options));
        assertEquals("OrderItemDO", JavaEntityGenerator.className("orderItem", options));
        assertEquals("T2023LogDO", JavaEntityGenerator.className("2023_log", options));
        // 去掉前缀后为空时保留原名
        assertEquals("TDO", JavaEntityGenerator.className("t_", options));
        assertEquals("XmlDataDO", JavaEntityGenerator.className("XMLData", options));

        EntityOptions longest = EntityOptions.builder().tablePrefixes("t_,t_sys_").build();
        assertEquals("Config", JavaEntityGenerator.className("t_sys_config", longest));
    }

    @Test
    void derivesFieldNamesWithSaneAcronyms() {
        assertEquals("userName", JavaEntityGenerator.fieldName("user_name"));
        assertEquals("userId", JavaEntityGenerator.fieldName("USER_ID"));
        assertEquals("userId", JavaEntityGenerator.fieldName("userID"));
        assertEquals("httpStatus", JavaEntityGenerator.fieldName("HTTPStatus"));
        assertEquals("xmlHttpRequest", JavaEntityGenerator.fieldName("XMLHttpRequest"));
        assertEquals("ipV4Addr", JavaEntityGenerator.fieldName("ip_v4_addr"));
        assertEquals("address2", JavaEntityGenerator.fieldName("address2"));
        assertEquals("orderName", JavaEntityGenerator.fieldName("Order Name"));
        assertEquals("createdAt", JavaEntityGenerator.fieldName("createdAt"));
        assertEquals("classValue", JavaEntityGenerator.fieldName("class"));
        assertEquals("_2faCode", JavaEntityGenerator.fieldName("2fa_code"));
        assertEquals("用户名", JavaEntityGenerator.fieldName("用户名"));
        assertEquals("field", JavaEntityGenerator.fieldName("$$"));
    }

    // ==========================================
    // 类型映射
    // ==========================================

    @Test
    void mapsSqlTypesToJavaTypes() {
        String ddl = "CREATE TABLE m (\n"
                + " a tinyint(1), b bit(1), c boolean, d tinyint, e smallint, f int, g int unsigned,\n"
                + " h bigint, i bigint unsigned, j decimal(10,2), k numeric, l float, m double,\n"
                + " n double precision, o char(2), p text, q clob, r enum('x','y'), s jsonb, t uuid,\n"
                + " u date, v time, w datetime, x timestamp, y timestamptz, z timestamp with time zone,\n"
                + " aa blob, ab bytea, ac varbinary(16), ad year, ae geometry, af text[], ag serial,\n"
                + " ah number(9), ai number(10,0), aj number(19), ak number(8,2), al number, am float(53),\n"
                + " an bit(8), ao varchar2(10), ap nvarchar(10), aq real, ar mediumint unsigned\n"
                + ")";
        TableDef table = parser.parse(ddl).tables().get(0);

        String[][] expected = {
                {"a", "Boolean"}, {"b", "Boolean"}, {"c", "Boolean"}, {"d", "Integer"}, {"e", "Integer"},
                {"f", "Integer"}, {"g", "Long"}, {"h", "Long"}, {"i", "Long"}, {"j", "BigDecimal"},
                {"k", "BigDecimal"}, {"l", "Float"}, {"m", "Double"}, {"n", "Double"}, {"o", "String"},
                {"p", "String"}, {"q", "String"}, {"r", "String"}, {"s", "String"}, {"t", "UUID"},
                {"u", "LocalDate"}, {"v", "LocalTime"}, {"w", "LocalDateTime"}, {"x", "LocalDateTime"},
                {"y", "OffsetDateTime"}, {"z", "OffsetDateTime"}, {"aa", "byte[]"}, {"ab", "byte[]"},
                {"ac", "byte[]"}, {"ad", "Integer"}, {"ae", "Object"}, {"af", "String[]"}, {"ag", "Integer"},
                {"ah", "Integer"}, {"ai", "Long"}, {"aj", "BigDecimal"}, {"ak", "BigDecimal"},
                {"al", "BigDecimal"}, {"am", "Double"}, {"an", "byte[]"}, {"ao", "String"}, {"ap", "String"},
                {"aq", "Float"}, {"ar", "Integer"},
        };
        for (String[] row : expected) {
            ColumnDef column = table.column(row[0]).orElseThrow();
            assertEquals(row[1], JavaTypeMapper.map(column, false, false).simpleName(),
                    "column " + row[0] + " (" + column.declaredType() + ")");
        }

        ColumnDef unsignedBig = table.column("i").orElseThrow();
        assertEquals(JavaTypeMapper.Note.UNSIGNED_BIGINT, JavaTypeMapper.map(unsignedBig, false, false).note());
        assertEquals("BigInteger", JavaTypeMapper.map(unsignedBig, false, true).simpleName());
        assertEquals(JavaTypeMapper.Note.UNKNOWN,
                JavaTypeMapper.map(table.column("ae").orElseThrow(), false, false).note());
        assertEquals("Date", JavaTypeMapper.map(table.column("y").orElseThrow(), true, false).simpleName());
        assertEquals("java.util.Date",
                JavaTypeMapper.map(table.column("u").orElseThrow(), true, false).qualifiedName());
    }

    @Test
    void reportsUnknownTypeAndUnsignedBigint() {
        Result result = generate("CREATE TABLE g (id BIGINT UNSIGNED PRIMARY KEY, shape GEOMETRY)",
                EntityOptions.defaults());

        assertEquals(List.of(WarningCode.UNSIGNED_BIGINT, WarningCode.UNKNOWN_TYPE), codes(result));
        assertEquals("GEOMETRY", result.warnings().get(1).detail());
        assertTrue(javaOf(result, 0).contains("private Object shape;"));

        Result big = generate("CREATE TABLE g (id BIGINT UNSIGNED PRIMARY KEY)",
                EntityOptions.builder().unsignedBigintAsBigInteger(true).build());
        assertTrue(big.warnings().isEmpty());
        assertTrue(javaOf(big, 0).contains("import java.math.BigInteger;"));
        assertTrue(javaOf(big, 0).contains("private BigInteger id;"));
    }

    // ==========================================
    // 风格与注解
    // ==========================================

    @Test
    void lombokStyleWithJpaJakarta() {
        EntityOptions options = EntityOptions.builder()
                .packageName("com.demo.entity").tablePrefixes("t_")
                .style(Style.LOMBOK).lombokBuilder(true).lombokNoArgsConstructor(true).lombokAllArgsConstructor(true)
                .annotations(Annotations.JPA).jpaNamespace(JpaNamespace.JAKARTA)
                .serializable(true).build();

        Result result = generate(MYSQL, options);
        String java = javaOf(result, 0);

        assertEquals("User.java", result.files().get(0).fileName());
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
        assertTrue(java.startsWith("package com.demo.entity;\n\n"));
        assertTrue(java.contains("import jakarta.persistence.Column;\n"));
        assertTrue(java.contains("import lombok.Data;\n"));
        assertTrue(java.contains("import lombok.NoArgsConstructor;\n\nimport java.io.Serializable;\n"),
                "third-party imports first, then java.*: " + java);
        assertTrue(java.contains(" * 用户表\n *\n * Table: t_user\n"));
        assertTrue(java.contains("@Data\n@Builder\n@NoArgsConstructor\n@AllArgsConstructor\n@Entity\n"
                + "@Table(name = \"t_user\")\npublic class User implements Serializable {\n"));
        assertTrue(java.contains("private static final long serialVersionUID = 1L;"));
        assertTrue(java.contains("    /**\n     * 主键\n     */\n    @Id\n"
                + "    @GeneratedValue(strategy = GenerationType.IDENTITY)\n    private Long id;\n"));
        assertTrue(java.contains("@Column(name = \"user_name\", nullable = false, length = 64)\n    private String userName;"));
        assertTrue(java.contains("@Column(unique = true, length = 128)\n    private String email;"));
        assertTrue(java.contains("@Column(name = \"is_deleted\", nullable = false)\n    private Boolean isDeleted;"));
        assertTrue(java.contains("@Column(precision = 12, scale = 2)\n    private BigDecimal balance;"));
        assertTrue(java.contains("    @Lob\n    private byte[] avatar;"));
        assertTrue(java.contains("    @Lob\n    private String bio;"));
        // 列名只差大小写时不必写 name
        assertTrue(java.contains("\n    private Integer userId;"));
        assertFalse(java.contains("getId()"), "Lombok style must not emit accessors");
        assertFalse(java.contains("javax."));
    }

    @Test
    void jpaJavaxWithAlwaysColumnNameAndLegacyDate() {
        EntityOptions options = EntityOptions.builder()
                .style(Style.PLAIN).annotations(Annotations.JPA).jpaNamespace(JpaNamespace.JAVAX)
                .jpaAlwaysColumn(true).legacyDate(true).build();

        String java = javaOf(generate(MYSQL, options), 0);

        assertTrue(java.contains("import javax.persistence.Entity;"));
        assertFalse(java.contains("jakarta"));
        assertTrue(java.contains("@Column(name = \"id\")\n    private Long id;"));
        assertTrue(java.contains("@Temporal(TemporalType.DATE)\n    @Column(name = \"birthday\")\n    private Date birthday;"));
        assertTrue(java.contains("@Temporal(TemporalType.TIME)"));
        assertTrue(java.contains("@Temporal(TemporalType.TIMESTAMP)\n    @Column(name = \"created_at\", nullable = false)"));
        assertTrue(java.contains("import java.util.Date;"));
        assertFalse(java.contains("java.time"));
    }

    @Test
    void mybatisPlusAnnotations() {
        EntityOptions options = EntityOptions.builder()
                .tablePrefixes("t_").annotations(Annotations.MYBATIS_PLUS).build();

        String java = javaOf(generate(MYSQL, options), 0);

        assertTrue(java.contains("import com.baomidou.mybatisplus.annotation.IdType;"));
        assertTrue(java.contains("@TableName(\"t_user\")\npublic class User {"));
        assertTrue(java.contains("@TableId(value = \"id\", type = IdType.AUTO)\n    private Long id;"));
        // user_name 符合默认驼峰映射，不需要 @TableField
        assertTrue(java.contains("     */\n    private String userName;"));
        // userID 按驼峰映射会变成 user_id，与实际列名不符
        assertTrue(java.contains("@TableField(\"userID\")\n    private Integer userId;"));

        String assigned = javaOf(generate("CREATE TABLE s.k (code VARCHAR(32) PRIMARY KEY, ref UUID)",
                options), 0);
        assertTrue(assigned.contains("@TableName(value = \"k\", schema = \"s\")"));
        assertTrue(assigned.contains("@TableId(value = \"code\", type = IdType.ASSIGN_ID)"));
    }

    @Test
    void compositeKeyHandling() {
        String ddl = "CREATE TABLE user_role (user_id BIGINT, role_id INT, PRIMARY KEY (user_id, role_id))";

        Result jpa = generate(ddl, EntityOptions.builder().annotations(Annotations.JPA).build());
        String jpaJava = javaOf(jpa, 0);
        assertEquals(List.of(WarningCode.COMPOSITE_KEY_JPA), codes(jpa));
        assertEquals("user_id, role_id", jpa.warnings().get(0).detail());
        assertTrue(jpaJava.contains("// Composite primary key (user_id, role_id): JPA requires @IdClass"));
        assertEquals(2, jpaJava.split("@Id\n", -1).length - 1);
        assertFalse(jpaJava.contains("@GeneratedValue"));

        Result mp = generate(ddl, EntityOptions.builder().annotations(Annotations.MYBATIS_PLUS).build());
        assertEquals(List.of(WarningCode.COMPOSITE_KEY_MYBATIS_PLUS), codes(mp));
        assertFalse(javaOf(mp, 0).contains("@TableId"));

        Result noKey = generate("CREATE TABLE log (msg TEXT)", EntityOptions.builder().annotations(Annotations.JPA).build());
        assertEquals(List.of(WarningCode.NO_PRIMARY_KEY_JPA), codes(noKey));
    }

    @Test
    void recordStyleOmitsJpaButKeepsMybatisPlus() {
        Result jpa = generate(MYSQL, EntityOptions.builder().style(Style.RECORD).annotations(Annotations.JPA).build());
        assertEquals(List.of(WarningCode.RECORD_WITH_JPA), codes(jpa));
        assertFalse(javaOf(jpa, 0).contains("@Entity"));

        String mp = javaOf(generate(MYSQL, EntityOptions.builder().style(Style.RECORD)
                .annotations(Annotations.MYBATIS_PLUS).build()), 0);
        assertTrue(mp.contains("public record TUser(\n        @TableId(value = \"id\", type = IdType.AUTO) Long id,\n"));
        assertTrue(mp.contains(" * @param id 主键\n"));
    }

    @Test
    void plainStyleGeneratesAccessorsAndToString() {
        String java = javaOf(generate("CREATE TABLE p (id INT, data BLOB, is_ok BOOLEAN)",
                EntityOptions.builder().style(Style.PLAIN).plainToString(true).build()), 0);

        assertTrue(java.contains("    public Integer getId() {\n        return id;\n    }\n"));
        assertTrue(java.contains("    public void setIsOk(Boolean isOk) {\n        this.isOk = isOk;\n    }\n"));
        assertTrue(java.contains("                + \"id=\" + id\n                + \", data=\" + Arrays.toString(data)\n"));
        assertTrue(java.contains("import java.util.Arrays;"));

        String noToString = javaOf(generate("CREATE TABLE p (id INT)",
                EntityOptions.builder().style(Style.PLAIN).plainToString(false).build()), 0);
        assertFalse(noToString.contains("toString"));
    }

    @Test
    void escapesJavadocAndAnnotationStrings() {
        String ddl = "CREATE TABLE `we\"ird` (\n"
                + "  id INT PRIMARY KEY COMMENT 'ends */ early',\n"
                + "  path VARCHAR(10) COMMENT 'C:\\\\users\\\\uxyz',\n"
                + "  note VARCHAR(10) COMMENT '@deprecated\\nsecond line'\n"
                + ") COMMENT = 'multi\\nline */ table'";
        String java = javaOf(generate(ddl, EntityOptions.builder().annotations(Annotations.JPA).build()), 0);

        assertTrue(java.contains("ends *&#47; early"));
        assertTrue(java.contains("C:&#92;users&#92;uxyz"), java);
        assertFalse(java.contains("\\u"));
        assertTrue(java.contains("     * {@literal @}deprecated\n     * second line\n"));
        assertTrue(java.contains(" * multi\n * line *&#47; table\n"));
        assertTrue(java.contains("@Table(name = \"we\\\"ird\")"));
    }

    @Test
    void dedupesClassesAndFieldsAndValidatesPackage() {
        Result result = generate("CREATE TABLE t_user (id INT); CREATE TABLE `user` (user_id INT, userId INT)",
                EntityOptions.builder().tablePrefixes("t_").packageName("com.bad-name").build());

        assertEquals(List.of("User.java", "User2.java"),
                result.files().stream().map(GeneratedFile::fileName).toList());
        assertEquals(List.of(WarningCode.INVALID_PACKAGE, WarningCode.DUPLICATE_CLASS, WarningCode.DUPLICATE_FIELD),
                codes(result));
        assertFalse(javaOf(result, 0).contains("package "));
        assertTrue(javaOf(result, 1).contains("private Integer userId2;"));
    }

    @Test
    void optionalJavadocCanBeTurnedOff() {
        String java = javaOf(generate(MYSQL, EntityOptions.builder().javadoc(false).build()), 0);

        assertFalse(java.contains("/**"));
        assertFalse(java.contains("用户"));
    }

    // ==========================================
    // MyBatis XML
    // ==========================================

    @Test
    void generatesWellFormedMapperXml() throws Exception {
        EntityOptions options = EntityOptions.builder()
                .packageName("com.demo.entity").tablePrefixes("t_").classSuffix("DO").mybatisXml(true).build();

        Result result = generate(MYSQL + "\nCREATE TABLE `a&b` (`x<y` INT COMMENT 'a -- b-')", options);

        List<GeneratedFile> xmlFiles = result.files().stream().filter(f -> f.kind() == FileKind.MAPPER_XML).toList();
        assertEquals(List.of("UserMapper.xml", "ABMapper.xml"),
                xmlFiles.stream().map(GeneratedFile::fileName).toList());
        String xml = xmlFiles.get(0).content();
        assertTrue(xml.contains("<mapper namespace=\"com.demo.mapper.UserMapper\">"));
        assertTrue(xml.contains("<resultMap id=\"BaseResultMap\" type=\"com.demo.entity.UserDO\">"));
        assertTrue(xml.contains("<id column=\"id\" property=\"id\" jdbcType=\"BIGINT\"/>"));
        assertTrue(xml.contains("<result column=\"user_name\" property=\"userName\" jdbcType=\"VARCHAR\"/>"));
        assertTrue(xml.contains("<result column=\"is_deleted\" property=\"isDeleted\" jdbcType=\"BIT\"/>"));
        assertTrue(xml.contains("<sql id=\"Base_Column_List\">\n        id, user_name, email,"));
        for (GeneratedFile file : xmlFiles) {
            Document document = parseXml(file.content());
            assertEquals("mapper", document.getDocumentElement().getTagName());
        }
        assertTrue(xmlFiles.get(1).content().contains("column=\"x&lt;y\""));

        String recordXml = generate(MYSQL, options.toBuilder().style(Style.RECORD).build()).files().get(1).content();
        assertTrue(recordXml.contains("<constructor>\n            <idArg column=\"id\" javaType=\"java.lang.Long\""));
        assertTrue(recordXml.contains("javaType=\"_byte[]\""));
        parseXml(recordXml);
    }

    @Test
    void mapperNamespaceDerivation() {
        assertEquals("com.a.mapper.UserMapper", JavaEntityGenerator.mapperNamespace("com.a.entity", "UserMapper"));
        assertEquals("com.a.dao.mapper.UserMapper", JavaEntityGenerator.mapperNamespace("com.a.dao", "UserMapper"));
        assertEquals("mapper.UserMapper", JavaEntityGenerator.mapperNamespace("model", "UserMapper"));
        assertEquals("UserMapper", JavaEntityGenerator.mapperNamespace("", "UserMapper"));
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    // ==========================================
    // 编译检查：生成的源码必须能直接通过 javac
    // ==========================================

    /** 表名故意与 java.util.Date、java.lang.String、Serializable、Arrays 撞名 */
    private static final String CONFLICTS = MYSQL + "\n"
            + "CREATE TABLE `date` (id INT PRIMARY KEY, day DATE, at TIMESTAMP);\n"
            + "CREATE TABLE `string` (name VARCHAR(10), `class` INT, `hashCode` INT, `2fa` INT);\n"
            + "CREATE TABLE `serializable` (id INT);\n"
            + "CREATE TABLE `arrays` (raw BLOB, names TEXT[], grid INT[][]);\n"
            + "CREATE TABLE `weird_doc` (x INT COMMENT 'C:\\\\users\\\\u12 */ @see <b>&');\n"
            + "CREATE TABLE `pg` (id UUID, t TIMESTAMPTZ, tt TIME WITH TIME ZONE, big BIGINT UNSIGNED, g POINT);\n"
            + "CREATE TABLE `用户` (`名字` VARCHAR(10));";

    @Test
    void plainStyleCompiles(@TempDir Path dir) throws IOException {
        EntityOptions base = EntityOptions.builder().packageName("com.demo.entity").style(Style.PLAIN)
                .plainToString(true).serializable(true).build();
        assertCompiles(dir.resolve("a"), generate(CONFLICTS, base));
        assertCompiles(dir.resolve("b"), generate(CONFLICTS, base.toBuilder().legacyDate(true)
                .unsignedBigintAsBigInteger(true).packageName("").serializable(false).build()));
    }

    @Test
    void recordStyleCompiles(@TempDir Path dir) throws IOException {
        EntityOptions base = EntityOptions.builder().packageName("com.demo").style(Style.RECORD)
                .serializable(true).tablePrefixes("t_").classSuffix("Record").build();
        assertCompiles(dir.resolve("a"), generate(CONFLICTS, base));
        assertCompiles(dir.resolve("b"), generate(CONFLICTS, base.toBuilder().serializable(false)
                .legacyDate(true).javadoc(false).build()));
    }

    private static void assertCompiles(Path dir, Result result) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests must run on a JDK");
        List<Path> sources = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        for (GeneratedFile file : result.files()) {
            if (file.kind() != FileKind.JAVA) {
                continue;
            }
            Path source = dir.resolve("src").resolve(file.fileName());
            Files.createDirectories(source.getParent());
            Files.writeString(source, file.content(), StandardCharsets.UTF_8);
            sources.add(source);
            all.append("// ").append(file.fileName()).append('\n').append(file.content()).append('\n');
        }
        Path out = Files.createDirectories(dir.resolve("out"));
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            boolean ok = compiler.getTask(null, files, diagnostics,
                    List.of("--release", "17", "-encoding", "UTF-8", "-proc:none", "-d", out.toString()),
                    null, files.getJavaFileObjectsFromPaths(sources)).call();
            String report = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> d.getSource().getName() + ":" + d.getLineNumber() + " " + d.getMessage(Locale.ROOT))
                    .collect(Collectors.joining("\n"));
            assertTrue(ok, report + "\n\n" + all);
        }
    }
}
