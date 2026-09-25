package com.aqishi.toolbox.feature.generation.domain;

import com.aqishi.toolbox.feature.generation.domain.EntityOptions.Annotations;
import com.aqishi.toolbox.feature.generation.domain.EntityOptions.Style;
import com.aqishi.toolbox.feature.generation.domain.JavaTypeMapper.JavaType;
import com.aqishi.toolbox.feature.generation.domain.JavaTypeMapper.Kind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 由 {@link TableDef} 生成 Java 实体源码（以及可选的 MyBatis Mapper XML）。
 *
 * <p>生成结果必须能直接编译，这决定了几处看似多余的处理：</p>
 * <ul>
 *   <li>类型引用统一经过 {@link Imports}：表名叫 {@code date}、{@code data}、{@code table} 时，
 *       生成的类名会与 {@code java.util.Date}、Lombok 的 {@code @Data}、JPA 的 {@code @Table}
 *       撞名，这时改写全限定名而不是 import，否则编译报「已在此编译单元中定义」。</li>
 *   <li>Javadoc 中的 {@code *}{@code /} 会提前结束注释，{@code \}{@code u} 会被编译器当作
 *       Unicode 转义（Windows 路径 {@code C:\}{@code users} 就能让整个文件编译失败），都要转义。</li>
 *   <li>列名转成字段名后可能是关键字（{@code class}、{@code default}）或以数字开头，
 *       record 组件还不能叫 {@code hashCode}、{@code toString} 等，统一改名。</li>
 * </ul>
 *
 * <p>联合主键的取舍：JPA 下每个主键列都标 {@code @Id} 并在类上留一行注释提示需要
 * {@code @IdClass} 或 {@code @EmbeddedId}——自动生成 IdClass 需要再产出一个类且命名易冲突，
 * 而标出每个主键列能让用户一眼看出该放进 IdClass 的是哪些字段。MyBatis-Plus 不支持联合主键，
 * 因此不写 {@code @TableId}（只标其中一列会让按主键更新时漏掉条件，比不标更危险）。
 * 两种情况都会产生告警。</p>
 *
 * <p>JPA 实体要求无参构造与可变字段，record 无法满足；RECORD 风格下选择 JPA 时省略 JPA 注解并告警。</p>
 *
 * <p>本类不产出面向用户的文案：告警以 {@link WarningCode} 表达，由界面层本地化。
 * 生成代码里的注释文本（{@code Table: t_user}、联合主键提示）是代码的一部分，使用英文。</p>
 */
public final class JavaEntityGenerator {

    /** 生成文件的类别 */
    public enum FileKind { JAVA, MAPPER_XML }

    /**
     * 一个生成文件。
     *
     * @param tableName 来源表名
     * @param className 实体类名
     * @param fileName  建议的文件名，如 {@code User.java}、{@code UserMapper.xml}
     * @param kind      文件类别
     * @param content   文件内容（换行统一为 \n）
     */
    public record GeneratedFile(String tableName, String className, String fileName, FileKind kind,
                                String content) {
    }

    /** 生成告警的类别 */
    public enum WarningCode {
        /** 不认识的列类型，映射为 Object；detail 为原类型写法 */
        UNKNOWN_TYPE,
        /** bigint unsigned 映射为 Long，超过 Long.MAX_VALUE 的值会溢出 */
        UNSIGNED_BIGINT,
        /** JPA 联合主键需要 @IdClass 或 @EmbeddedId；detail 为主键列清单 */
        COMPOSITE_KEY_JPA,
        /** MyBatis-Plus 不支持联合主键，未生成 @TableId；detail 为主键列清单 */
        COMPOSITE_KEY_MYBATIS_PLUS,
        /** JPA 实体没有主键，@Entity 缺 @Id 无法启动 */
        NO_PRIMARY_KEY_JPA,
        /** record 不能作为 JPA 实体，已省略 JPA 注解 */
        RECORD_WITH_JPA,
        /** 包名不合法，已省略 package 语句；detail 为原包名 */
        INVALID_PACKAGE,
        /** 类名重复，已追加序号；detail 为最终类名 */
        DUPLICATE_CLASS,
        /** 字段名重复（如 user_id 与 userId），已追加序号；detail 为最终字段名 */
        DUPLICATE_FIELD
    }

    /**
     * 一条生成告警。
     *
     * @param code   类别
     * @param table  相关表名，可为 null
     * @param column 相关列名，可为 null
     * @param detail 补充信息（类型写法、列清单等），可为 null
     */
    public record Warning(WarningCode code, String table, String column, String detail) {
    }

    /** 生成结果：文件按「表顺序，每张表先 Java 后 XML」排列 */
    public record Result(List<GeneratedFile> files, List<Warning> warnings) {
        public Result {
            files = List.copyOf(files);
            warnings = List.copyOf(warnings);
        }
    }

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "_");

    /** record 组件名会生成同名访问器，与 Object 的这些方法冲突时编译失败 */
    private static final Set<String> RECORD_FORBIDDEN_COMPONENTS = Set.of(
            "clone", "finalize", "getClass", "hashCode", "notify", "notifyAll", "toString", "wait");

    /** 这些类型在 JPA 下标 @Lob；PostgreSQL 的 text/bytea 不是 LOB，标了反而会被映射成 oid */
    private static final Set<String> LOB_TYPES = Set.of(
            "clob", "nclob", "dbclob", "blob", "tinyblob", "mediumblob", "longblob", "mediumtext", "longtext");

    /** 包名以这些段结尾时，Mapper XML 的 namespace 把它替换为 mapper */
    private static final Set<String> ENTITY_PACKAGE_SEGMENTS = Set.of(
            "entity", "entities", "domain", "model", "models", "po", "pojo", "dataobject", "bean", "beans");

    private static final int COLUMN_LIST_WIDTH = 100;

    /**
     * 为一组表生成源码。
     *
     * @param tables  解析出的表
     * @param options 生成选项
     */
    public Result generate(List<TableDef> tables, EntityOptions options) {
        List<GeneratedFile> files = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();
        String packageName = options.packageName();
        if (!packageName.isEmpty() && !isValidPackage(packageName)) {
            warnings.add(new Warning(WarningCode.INVALID_PACKAGE, null, null, packageName));
            packageName = "";
        }
        // 按小写去重：Windows/macOS 默认文件系统不区分大小写，User.java 与 USER.java 会互相覆盖
        Set<String> usedClassNames = new HashSet<>();
        List<String> classNames = new ArrayList<>();
        for (TableDef table : tables) {
            String className = className(table.name(), options);
            if (!usedClassNames.add(className.toLowerCase(Locale.ROOT))) {
                String base = className;
                int n = 2;
                while (!usedClassNames.add((base + n).toLowerCase(Locale.ROOT))) {
                    n++;
                }
                className = base + n;
                warnings.add(new Warning(WarningCode.DUPLICATE_CLASS, table.name(), null, className));
            }
            classNames.add(className);
        }
        Set<String> siblings = Set.copyOf(classNames);
        for (int t = 0; t < tables.size(); t++) {
            TableDef table = tables.get(t);
            String className = classNames.get(t);
            List<Field> fields = buildFields(table, options, warnings);
            addTableWarnings(table, options, warnings);
            String java = new JavaWriter(table, className, packageName, siblings, fields, options).write();
            files.add(new GeneratedFile(table.name(), className, className + ".java", FileKind.JAVA, java));
            if (options.mybatisXml()) {
                String mapperName = mapperName(className, options.classSuffix());
                String xml = mapperXml(table, className, mapperName, packageName, fields, options);
                files.add(new GeneratedFile(table.name(), className, mapperName + ".xml", FileKind.MAPPER_XML, xml));
            }
        }
        return new Result(files, warnings);
    }

    // ==========================================
    // 命名
    // ==========================================

    /**
     * 表名转类名：去前缀、按下划线与驼峰边界切词、每个词首字母大写，再加后缀。
     * 例如 {@code t_user_info} 去掉 {@code t_} 后为 {@code UserInfo}。
     */
    public static String className(String tableName, EntityOptions options) {
        String stripped = stripPrefix(tableName, options.tablePrefixes());
        StringBuilder name = new StringBuilder();
        for (String word : words(stripped)) {
            name.append(capitalize(word.toLowerCase(Locale.ROOT)));
        }
        if (name.length() == 0) {
            name.append("Table");
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            name.insert(0, 'T');
        }
        for (char c : options.classSuffix().toCharArray()) {
            if (Character.isJavaIdentifierPart(c)) {
                name.append(c);
            }
        }
        return name.toString();
    }

    /**
     * 列名转字段名（小驼峰）。
     *
     * <p>先按非字母数字切开，再在驼峰边界切开，所有词统一转小写后重新拼接：
     * {@code user_name} 为 {@code userName}，{@code USER_ID}（Oracle 习惯全大写）为 {@code userId}，
     * {@code userID} 为 {@code userId}，{@code HTTPStatus} 为 {@code httpStatus}——
     * 缩写不会变成 {@code hTTPStatus} 这种样子。关键字追加 {@code Value}，
     * 数字开头的前面加下划线。</p>
     */
    public static String fieldName(String columnName) {
        StringBuilder name = new StringBuilder();
        for (String word : words(columnName)) {
            String lower = word.toLowerCase(Locale.ROOT);
            name.append(name.length() == 0 ? lower : capitalize(lower));
        }
        if (name.length() == 0) {
            name.append("field");
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            name.insert(0, '_');
        }
        String result = name.toString();
        return JAVA_KEYWORDS.contains(result) ? result + "Value" : result;
    }

    /** 按非字母数字字符与驼峰边界切词 */
    static List<String> words(String raw) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int length = raw == null ? 0 : raw.length();
        for (int i = 0; i < length; i++) {
            char c = raw.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                flush(words, current);
                continue;
            }
            if (current.length() > 0 && Character.isUpperCase(c)) {
                char prev = raw.charAt(i - 1);
                boolean afterLower = Character.isLowerCase(prev) || Character.isDigit(prev);
                // XMLHttp：大写串后面接小写时，最后一个大写字母属于下一个词
                boolean acronymEnd = Character.isUpperCase(prev) && i + 1 < length
                        && Character.isLowerCase(raw.charAt(i + 1));
                if (afterLower || acronymEnd) {
                    flush(words, current);
                }
            }
            current.append(c);
        }
        flush(words, current);
        return words;
    }

    private static void flush(List<String> words, StringBuilder current) {
        if (current.length() > 0) {
            words.add(current.toString());
            current.setLength(0);
        }
    }

    private static String stripPrefix(String tableName, List<String> prefixes) {
        List<String> sorted = new ArrayList<>(prefixes);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        String lower = tableName.toLowerCase(Locale.ROOT);
        for (String prefix : sorted) {
            String p = prefix.toLowerCase(Locale.ROOT);
            if (!p.isEmpty() && lower.startsWith(p) && tableName.length() > p.length()) {
                return tableName.substring(p.length());
            }
        }
        return tableName;
    }

    private static String capitalize(String word) {
        if (word.isEmpty()) {
            return word;
        }
        return word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
    }

    /** userName 对应的下划线列名 user_name（MyBatis-Plus 默认的驼峰映射规则） */
    private static String toUnderscore(String field) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    out.append('_');
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isValidPackage(String name) {
        for (String segment : name.split("\\.", -1)) {
            if (segment.isEmpty() || JAVA_KEYWORDS.contains(segment)
                    || !Character.isJavaIdentifierStart(segment.charAt(0))) {
                return false;
            }
            for (char c : segment.toCharArray()) {
                if (!Character.isJavaIdentifierPart(c)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String mapperName(String className, String suffix) {
        String base = className;
        if (!suffix.isEmpty() && className.endsWith(suffix) && className.length() > suffix.length()) {
            base = className.substring(0, className.length() - suffix.length());
        }
        return base + "Mapper";
    }

    // ==========================================
    // 字段
    // ==========================================

    private record Field(ColumnDef column, String name, JavaType type) {
    }

    private List<Field> buildFields(TableDef table, EntityOptions options, List<Warning> warnings) {
        List<Field> fields = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (ColumnDef column : table.columns()) {
            String name = fieldName(column.name());
            if (options.style() == Style.RECORD && RECORD_FORBIDDEN_COMPONENTS.contains(name)) {
                name = name + "Value";
            }
            if (!used.add(name)) {
                String base = name;
                int n = 2;
                while (!used.add(base + n)) {
                    n++;
                }
                name = base + n;
                warnings.add(new Warning(WarningCode.DUPLICATE_FIELD, table.name(), column.name(), name));
            }
            JavaType type = JavaTypeMapper.map(column, options.legacyDate(), options.unsignedBigintAsBigInteger());
            if (type.note() == JavaTypeMapper.Note.UNKNOWN) {
                warnings.add(new Warning(WarningCode.UNKNOWN_TYPE, table.name(), column.name(), column.declaredType()));
            } else if (type.note() == JavaTypeMapper.Note.UNSIGNED_BIGINT) {
                warnings.add(new Warning(WarningCode.UNSIGNED_BIGINT, table.name(), column.name(), column.declaredType()));
            }
            fields.add(new Field(column, name, type));
        }
        return fields;
    }

    private static void addTableWarnings(TableDef table, EntityOptions options, List<Warning> warnings) {
        String keys = String.join(", ", table.primaryKey());
        if (options.annotations() == Annotations.JPA) {
            if (options.style() == Style.RECORD) {
                warnings.add(new Warning(WarningCode.RECORD_WITH_JPA, table.name(), null, null));
            } else if (table.primaryKey().isEmpty()) {
                warnings.add(new Warning(WarningCode.NO_PRIMARY_KEY_JPA, table.name(), null, null));
            } else if (table.hasCompositeKey()) {
                warnings.add(new Warning(WarningCode.COMPOSITE_KEY_JPA, table.name(), null, keys));
            }
        } else if (options.annotations() == Annotations.MYBATIS_PLUS && table.hasCompositeKey()) {
            warnings.add(new Warning(WarningCode.COMPOSITE_KEY_MYBATIS_PLUS, table.name(), null, keys));
        }
    }

    // ==========================================
    // Java 源码
    // ==========================================

    /**
     * 类型引用登记处：决定一个类型写简单名（并登记 import）还是写全限定名。
     *
     * <p>简单名与生成的类名相同、或与已登记的另一个类型同名时，改写全限定名。
     * java.lang 的类型还要避开同一批生成的其他类名：同包的类型优先于 java.lang 的隐式导入，
     * 同一包里生成了 {@code String} 类，别的实体里的 {@code String} 就都指向它了。
     * 显式 import 的类型不受此影响（单类型导入优先于同包类型）。</p>
     */
    private static final class Imports {
        private final String className;
        private final String packageName;
        private final Set<String> siblings;
        private final Map<String, String> bySimpleName = new HashMap<>();
        private final Set<String> imports = new TreeSet<>();

        Imports(String className, String packageName, Set<String> siblings) {
            this.className = className;
            this.packageName = packageName;
            this.siblings = siblings;
        }

        String use(String qualifiedName) {
            int dot = qualifiedName.lastIndexOf('.');
            if (dot < 0) {
                return qualifiedName;
            }
            String simple = qualifiedName.substring(dot + 1);
            String owner = qualifiedName.substring(0, dot);
            if (simple.equals(className) || ("java.lang".equals(owner) && siblings.contains(simple))) {
                return qualifiedName;
            }
            String existing = bySimpleName.putIfAbsent(simple, qualifiedName);
            if (existing != null && !existing.equals(qualifiedName)) {
                return qualifiedName;
            }
            if (!"java.lang".equals(owner) && !owner.equals(packageName)) {
                imports.add(qualifiedName);
            }
            return simple;
        }

        /** 第三方在前、java.* 在后，两组之间空一行 */
        void writeTo(StringBuilder out) {
            List<String> java = new ArrayList<>();
            List<String> other = new ArrayList<>();
            for (String name : imports) {
                (name.startsWith("java.") ? java : other).add(name);
            }
            for (String name : other) {
                out.append("import ").append(name).append(";\n");
            }
            if (!other.isEmpty() && !java.isEmpty()) {
                out.append('\n');
            }
            for (String name : java) {
                out.append("import ").append(name).append(";\n");
            }
            if (!imports.isEmpty()) {
                out.append('\n');
            }
        }
    }

    /** 一张表的 Java 源码写出器 */
    private static final class JavaWriter {
        private final TableDef table;
        private final String className;
        private final String packageName;
        private final List<Field> fields;
        private final EntityOptions options;
        private final Imports imports;
        private final boolean jpa;
        private final boolean mybatisPlus;
        private final String jpaPackage;

        JavaWriter(TableDef table, String className, String packageName, Set<String> siblings,
                   List<Field> fields, EntityOptions options) {
            this.table = table;
            this.className = className;
            this.packageName = packageName;
            this.fields = fields;
            this.options = options;
            this.imports = new Imports(className, packageName, siblings);
            this.jpa = options.annotations() == Annotations.JPA && options.style() != Style.RECORD;
            this.mybatisPlus = options.annotations() == Annotations.MYBATIS_PLUS;
            this.jpaPackage = options.jpaNamespace().packageName();
        }

        String write() {
            // 先写类体与注解，import 在此过程中登记，最后拼到文件头
            StringBuilder declaration = new StringBuilder();
            writeClassJavadoc(declaration);
            writeClassAnnotations(declaration);
            if (options.style() == Style.RECORD) {
                writeRecord(declaration);
            } else {
                writeClass(declaration);
            }

            StringBuilder out = new StringBuilder();
            if (!packageName.isEmpty()) {
                out.append("package ").append(packageName).append(";\n\n");
            }
            imports.writeTo(out);
            out.append(declaration);
            return out.toString();
        }

        private void writeClassJavadoc(StringBuilder out) {
            if (!options.javadoc()) {
                return;
            }
            List<String> lines = new ArrayList<>();
            if (table.hasComment()) {
                lines.addAll(docLines(table.comment()));
                lines.add("");
            }
            String qualified = table.schema() == null ? table.name() : table.schema() + "." + table.name();
            lines.add("Table: " + escapeDoc(qualified));
            if (options.style() == Style.RECORD) {
                boolean first = true;
                for (Field field : fields) {
                    if (!field.column().hasComment()) {
                        continue;
                    }
                    if (first) {
                        lines.add("");
                        first = false;
                    }
                    // @param 只能占一行的开头，多行注释压成一行
                    lines.add("@param " + field.name() + " "
                            + String.join(" ", docLines(field.column().comment())));
                }
            }
            javadoc(out, "", lines);
        }

        private void writeClassAnnotations(StringBuilder out) {
            if (options.style() == Style.LOMBOK) {
                out.append('@').append(imports.use("lombok.Data")).append('\n');
                if (options.lombokBuilder()) {
                    out.append('@').append(imports.use("lombok.Builder")).append('\n');
                }
                if (options.lombokNoArgsConstructor()) {
                    out.append('@').append(imports.use("lombok.NoArgsConstructor")).append('\n');
                }
                if (options.lombokAllArgsConstructor()) {
                    out.append('@').append(imports.use("lombok.AllArgsConstructor")).append('\n');
                }
            }
            if (jpa) {
                if (table.hasCompositeKey()) {
                    out.append("// Composite primary key (").append(String.join(", ", table.primaryKey()))
                            .append("): JPA requires @IdClass or @EmbeddedId; each key column is marked @Id.\n");
                }
                out.append('@').append(imports.use(jpaPackage + ".Entity")).append('\n');
                out.append('@').append(imports.use(jpaPackage + ".Table")).append("(name = ")
                        .append(javaString(table.name()));
                if (table.schema() != null) {
                    out.append(", schema = ").append(javaString(table.schema()));
                }
                out.append(")\n");
            }
            if (mybatisPlus) {
                String tableName = imports.use("com.baomidou.mybatisplus.annotation.TableName");
                if (table.schema() == null) {
                    out.append('@').append(tableName).append('(').append(javaString(table.name())).append(")\n");
                } else {
                    out.append('@').append(tableName).append("(value = ").append(javaString(table.name()))
                            .append(", schema = ").append(javaString(table.schema())).append(")\n");
                }
            }
        }

        private String implementsClause() {
            return options.serializable() ? " implements " + imports.use("java.io.Serializable") : "";
        }

        private void writeSerialVersion(StringBuilder out) {
            if (options.serializable()) {
                out.append("\n    private static final long serialVersionUID = 1L;\n");
            }
        }

        private void writeClass(StringBuilder out) {
            out.append("public class ").append(className).append(implementsClause()).append(" {\n");
            writeSerialVersion(out);
            for (Field field : fields) {
                out.append('\n');
                if (options.javadoc() && field.column().hasComment()) {
                    javadoc(out, "    ", docLines(field.column().comment()));
                }
                for (String annotation : fieldAnnotations(field)) {
                    out.append("    ").append(annotation).append('\n');
                }
                out.append("    private ").append(typeRef(field.type())).append(' ').append(field.name()).append(";\n");
            }
            if (options.style() == Style.PLAIN) {
                for (Field field : fields) {
                    String type = typeRef(field.type());
                    String accessor = capitalize(field.name());
                    out.append('\n')
                            .append("    public ").append(type).append(" get").append(accessor).append("() {\n")
                            .append("        return ").append(field.name()).append(";\n")
                            .append("    }\n\n")
                            .append("    public void set").append(accessor).append('(').append(type).append(' ')
                            .append(field.name()).append(") {\n")
                            .append("        this.").append(field.name()).append(" = ").append(field.name()).append(";\n")
                            .append("    }\n");
                }
                if (options.plainToString()) {
                    writeToString(out);
                }
            }
            out.append("}\n");
        }

        private void writeToString(StringBuilder out) {
            out.append('\n')
                    .append("    @").append(imports.use("java.lang.Override")).append('\n')
                    .append("    public ").append(imports.use("java.lang.String")).append(" toString() {\n")
                    .append("        return \"").append(className).append("{\"");
            boolean first = true;
            for (Field field : fields) {
                out.append("\n                + \"").append(first ? "" : ", ").append(field.name()).append("=\" + ");
                if (field.type().isArray()) {
                    String arrays = imports.use("java.util.Arrays");
                    String method = field.type().arrayDims() > 1 ? "deepToString" : "toString";
                    out.append(arrays).append('.').append(method).append('(').append(field.name()).append(')');
                } else {
                    out.append(field.name());
                }
                first = false;
            }
            out.append("\n                + \"}\";\n")
                    .append("    }\n");
        }

        private void writeRecord(StringBuilder out) {
            out.append("public record ").append(className).append('(');
            for (int i = 0; i < fields.size(); i++) {
                Field field = fields.get(i);
                out.append("\n        ");
                for (String annotation : fieldAnnotations(field)) {
                    out.append(annotation).append(' ');
                }
                out.append(typeRef(field.type())).append(' ').append(field.name());
                if (i < fields.size() - 1) {
                    out.append(',');
                }
            }
            out.append(')').append(implementsClause()).append(" {\n");
            writeSerialVersion(out);
            out.append("}\n");
        }

        private List<String> fieldAnnotations(Field field) {
            List<String> annotations = new ArrayList<>();
            ColumnDef column = field.column();
            if (jpa) {
                if (column.primaryKey()) {
                    annotations.add('@' + imports.use(jpaPackage + ".Id"));
                    if (column.autoIncrement() && !table.hasCompositeKey()) {
                        annotations.add('@' + imports.use(jpaPackage + ".GeneratedValue") + "(strategy = "
                                + imports.use(jpaPackage + ".GenerationType") + ".IDENTITY)");
                    }
                }
                if (LOB_TYPES.contains(column.typeName())) {
                    annotations.add('@' + imports.use(jpaPackage + ".Lob"));
                }
                if (options.legacyDate() && field.type().kind().isTemporal()) {
                    annotations.add('@' + imports.use(jpaPackage + ".Temporal") + '('
                            + imports.use(jpaPackage + ".TemporalType") + '.' + temporalType(field.type().kind()) + ')');
                }
                List<String> attributes = columnAttributes(field);
                if (!attributes.isEmpty()) {
                    annotations.add('@' + imports.use(jpaPackage + ".Column") + '(' + String.join(", ", attributes) + ')');
                }
            }
            if (mybatisPlus) {
                if (column.primaryKey() && !table.hasCompositeKey()) {
                    annotations.add('@' + imports.use("com.baomidou.mybatisplus.annotation.TableId") + "(value = "
                            + javaString(column.name()) + ", type = "
                            + imports.use("com.baomidou.mybatisplus.annotation.IdType") + '.' + idType(field) + ')');
                } else if (!column.name().equalsIgnoreCase(toUnderscore(field.name()))) {
                    annotations.add('@' + imports.use("com.baomidou.mybatisplus.annotation.TableField") + '('
                            + javaString(column.name()) + ')');
                }
            }
            return annotations;
        }

        /** @Column 只写需要的属性：名字与字段名不同、非空、唯一、长度、精度 */
        private List<String> columnAttributes(Field field) {
            ColumnDef column = field.column();
            List<String> attributes = new ArrayList<>();
            if (options.jpaAlwaysColumn() || !column.name().equalsIgnoreCase(field.name())) {
                attributes.add("name = " + javaString(column.name()));
            }
            if (!column.nullable() && !column.primaryKey()) {
                attributes.add("nullable = false");
            }
            if (column.unique() && !column.primaryKey()) {
                attributes.add("unique = true");
            }
            if (field.type().lengthApplies() && column.length() != null && !column.isArray()) {
                attributes.add("length = " + column.length());
            }
            if (field.type().kind() == Kind.BIG_DECIMAL && column.length() != null) {
                attributes.add("precision = " + column.length());
                if (column.scale() != null && column.scale() > 0) {
                    attributes.add("scale = " + column.scale());
                }
            }
            return attributes;
        }

        /** 自增用 AUTO；Long/String 主键用雪花 ASSIGN_ID；其他类型（UUID、Integer）需要自行赋值 */
        private static String idType(Field field) {
            if (field.column().autoIncrement()) {
                return "AUTO";
            }
            Kind kind = field.type().kind();
            if (!field.type().isArray() && (kind == Kind.LONG || kind == Kind.STRING)) {
                return "ASSIGN_ID";
            }
            return "INPUT";
        }

        private static String temporalType(Kind kind) {
            switch (kind) {
                case LOCAL_DATE:
                    return "DATE";
                case LOCAL_TIME:
                case OFFSET_TIME:
                    return "TIME";
                default:
                    return "TIMESTAMP";
            }
        }

        private String typeRef(JavaType type) {
            String base = type.kind() == Kind.BYTES ? "byte" : imports.use(type.qualifiedName());
            return base + "[]".repeat(type.arrayDims());
        }
    }

    // ==========================================
    // Javadoc / 字面量转义
    // ==========================================

    private static void javadoc(StringBuilder out, String indent, List<String> lines) {
        out.append(indent).append("/**\n");
        for (String line : lines) {
            out.append(indent).append(" *");
            if (!line.isEmpty()) {
                out.append(' ').append(line);
            }
            out.append('\n');
        }
        out.append(indent).append(" */\n");
    }

    /** 注释按行拆开并逐行转义，去掉首尾空行 */
    static List<String> docLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.strip().split("\\r?\\n|\\r")) {
            String escaped = escapeDoc(line.strip());
            // 行首的 @ 会被当成块标签
            if (escaped.startsWith("@")) {
                escaped = "{@literal @}" + escaped.substring(1);
            }
            lines.add(escaped);
        }
        return lines;
    }

    /**
     * 让任意文本能安全放进 Javadoc：{@code *}{@code /} 会结束注释；反斜杠加 u 会在词法分析前被当作
     * Unicode 转义，后面不是四位十六进制就是编译错误。
     */
    static String escapeDoc(String text) {
        return text.replace("*/", "*&#47;").replace("\\u", "&#92;u");
    }

    /** Java 字符串字面量 */
    static String javaString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.append('"').toString();
    }

    // ==========================================
    // MyBatis Mapper XML
    // ==========================================

    private static String mapperXml(TableDef table, String className, String mapperName, String packageName,
                                    List<Field> fields, EntityOptions options) {
        String namespace = mapperNamespace(packageName, mapperName);
        String type = packageName.isEmpty() ? className : packageName + "." + className;
        boolean record = options.style() == Style.RECORD;

        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" ")
                .append("\"https://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n")
                .append("<mapper namespace=\"").append(xmlAttr(namespace)).append("\">\n\n");
        String label = table.hasComment()
                ? table.name() + ": " + String.join(" ", docLines(table.comment()))
                : table.name();
        out.append("    <!-- ").append(xmlComment(label)).append(" -->\n");
        out.append("    <resultMap id=\"BaseResultMap\" type=\"").append(xmlAttr(type)).append("\">\n");
        if (record) {
            // record 没有 setter，只能走构造器映射；按组件顺序位置匹配，因此列出 javaType
            out.append("        <constructor>\n");
            for (Field field : fields) {
                out.append("            <").append(field.column().primaryKey() ? "idArg" : "arg")
                        .append(" column=\"").append(xmlAttr(field.column().name()))
                        .append("\" javaType=\"").append(xmlAttr(field.type().mybatisJavaType()))
                        .append("\" jdbcType=\"").append(field.type().jdbcType()).append("\"/>\n");
            }
            out.append("        </constructor>\n");
        } else {
            for (Field field : fields) {
                out.append("        <").append(field.column().primaryKey() ? "id" : "result")
                        .append(" column=\"").append(xmlAttr(field.column().name()))
                        .append("\" property=\"").append(xmlAttr(field.name()))
                        .append("\" jdbcType=\"").append(field.type().jdbcType()).append("\"/>\n");
            }
        }
        out.append("    </resultMap>\n\n");

        out.append("    <sql id=\"Base_Column_List\">\n");
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            String name = xmlText(fields.get(i).column().name()) + (i < fields.size() - 1 ? "," : "");
            if (line.length() > 0 && line.length() + 1 + name.length() > COLUMN_LIST_WIDTH) {
                out.append("        ").append(line).append('\n');
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(name);
        }
        if (line.length() > 0) {
            out.append("        ").append(line).append('\n');
        }
        out.append("    </sql>\n\n");
        out.append("</mapper>\n");
        return out.toString();
    }

    /** com.demo.entity + UserMapper 得到 com.demo.mapper.UserMapper */
    static String mapperNamespace(String packageName, String mapperName) {
        if (packageName.isEmpty()) {
            return mapperName;
        }
        int dot = packageName.lastIndexOf('.');
        String last = packageName.substring(dot + 1);
        String base = ENTITY_PACKAGE_SEGMENTS.contains(last.toLowerCase(Locale.ROOT))
                ? (dot < 0 ? "mapper" : packageName.substring(0, dot) + ".mapper")
                : packageName + ".mapper";
        return base + "." + mapperName;
    }

    private static String xmlText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String xmlAttr(String value) {
        return xmlText(value).replace("\"", "&quot;");
    }

    /** XML 注释里不能出现 -- ，也不能以 - 结尾 */
    private static String xmlComment(String value) {
        String text = value;
        while (text.contains("--")) {
            text = text.replace("--", "- -");
        }
        return text.endsWith("-") ? text + " " : text;
    }
}
