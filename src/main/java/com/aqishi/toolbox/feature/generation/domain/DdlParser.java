package com.aqishi.toolbox.feature.generation.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 建表语句（DDL）解析器：从 MySQL / PostgreSQL / Oracle / SQL Server 的 {@code CREATE TABLE}
 * 中提取表、列、主键与注释。
 *
 * <p>实现是「手写分词 + 按 token 递归下降」，而不是正则拼凑：DDL 里最容易出错的地方恰恰是
 * 正则最难处理的——字符串里的逗号和括号（{@code ENUM('a,b','c')}、{@code DEFAULT 'x(y)'}）、
 * 注释出现在任意位置、带引号的标识符里可以有空格。先把源文本切成 token，
 * 字符串、带引号标识符、注释都是整体，后面按括号深度切分就不会被误伤。</p>
 *
 * <p>它不是完整的 SQL 解析器：只关心生成实体需要的信息，其他语句（INSERT、DROP、SET、USE 等）
 * 与不认识的修饰一律跳过。例外是两类「补充信息」语句：PostgreSQL/Oracle 的
 * {@code COMMENT ON TABLE/COLUMN}，以及 pg_dump/Oracle 导出常见的
 * {@code ALTER TABLE ... ADD [CONSTRAINT x] PRIMARY KEY (...)}——这两种导出把注释和主键
 * 放在建表语句之外，不处理的话生成的实体既没有注释也没有 @Id。</p>
 *
 * <p>字符串字面量同时接受 {@code ''} 与反斜杠转义（MySQL 默认行为）。PostgreSQL 标准字符串里
 * 反斜杠不是转义符，以反斜杠结尾的字符串（{@code 'C:\'}）因此会被误判为未闭合——这在 DDL
 * 中极少出现，换来的是 MySQL 导出里 {@code COMMENT 'it\'s'} 这种常见写法能正确解析。</p>
 *
 * <p>本类是纯函数式的，不持有状态，可在任意线程复用。</p>
 */
public final class DdlParser {

    /** 可以出现在 CREATE 与 TABLE 之间的修饰词 */
    private static final Set<String> CREATE_TABLE_MODIFIERS = Set.of(
            "OR", "REPLACE", "GLOBAL", "LOCAL", "TEMPORARY", "TEMP", "UNLOGGED", "TRANSIENT");

    /** 一定是表级约束（保留字，不可能是未加引号的列名） */
    private static final Set<String> TABLE_CONSTRAINT_WORDS = Set.of(
            "CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "FULLTEXT", "SPATIAL", "EXCLUDE");

    /**
     * 可能是表级元素，也可能是未加引号的列名（PostgreSQL 中 key、index、period 不是保留字）。
     * 第二个 token 是已知类型名时按列处理：{@code key varchar(20)} 是列，{@code KEY idx (a)} 是索引。
     */
    private static final Set<String> AMBIGUOUS_ELEMENT_WORDS = Set.of("KEY", "INDEX", "PERIOD", "LIKE");

    /** 列定义中 {@code ::} 类型转换之后遇到这些词就停止吞并 DEFAULT 表达式 */
    private static final Set<String> COLUMN_OPTION_WORDS = Set.of(
            "NOT", "NULL", "PRIMARY", "UNIQUE", "REFERENCES", "CHECK", "COMMENT", "AUTO_INCREMENT",
            "AUTOINCREMENT", "ON", "COLLATE", "CHARSET", "CONSTRAINT", "GENERATED", "IDENTITY",
            "DEFAULT", "KEY", "VISIBLE", "INVISIBLE", "STORAGE", "COLUMN_FORMAT", "ENABLE", "DISABLE");

    /** 在类型名之后可能跟着的第二、第三个单词 */
    private static final Map<String, Set<String>> TYPE_CONTINUATIONS = Map.of(
            "double", Set.of("precision"),
            "character", Set.of("varying"),
            "char", Set.of("varying"),
            "nchar", Set.of("varying"),
            "national", Set.of("character", "char"),
            "national character", Set.of("varying"),
            "national char", Set.of("varying"),
            "bit", Set.of("varying"),
            "long", Set.of("raw", "varchar", "varbinary"));

    private static final Set<String> INTERVAL_FIELDS = Set.of(
            "YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND", "TO");

    /**
     * 解析 DDL 文本。
     *
     * @param sql 任意文本，可含多条语句；null 视为空
     * @return 解析结果，永不为 null
     */
    public DdlParseResult parse(String sql) {
        return new Run(sql == null ? "" : sql).execute();
    }

    // ==========================================
    // 分词
    // ==========================================

    enum Kind { WORD, QUOTED, STRING, NUMBER, SYMBOL }

    /**
     * 一个 token。
     *
     * @param text  WORD 为原文；QUOTED/STRING 为去掉引号并反转义后的内容；SYMBOL 为符号本身，
     *              其中数组标记统一为 {@code []}
     * @param start 在源文本中的起始偏移（含）
     * @param end   在源文本中的结束偏移（不含）
     */
    record Token(Kind kind, String text, int start, int end) {

        boolean isWord(String word) {
            return kind == Kind.WORD && text.equalsIgnoreCase(word);
        }

        boolean isSymbol(String symbol) {
            return kind == Kind.SYMBOL && text.equals(symbol);
        }

        boolean isIdentifier() {
            return kind == Kind.WORD || kind == Kind.QUOTED;
        }

        String upper() {
            return text.toUpperCase(Locale.ROOT);
        }
    }

    /** 带位置的语法错误；只在本类内部流转，最终转成 {@link DdlParseResult.Warning} */
    private static final class SyntaxError extends RuntimeException {
        private final int offset;

        SyntaxError(int offset, String message) {
            super(message, null, false, false);
            this.offset = offset;
        }
    }

    /** 一次解析的全部可变状态，保证 {@link DdlParser} 本身无状态 */
    private static final class Run {

        private final String src;
        private final int[] lineStarts;
        private final List<DdlParseResult.Warning> warnings = new ArrayList<>();
        private final List<MutableTable> tables = new ArrayList<>();
        /** COMMENT ON 与 ALTER TABLE 可能先于建表语句出现，统一在最后应用 */
        private final List<Runnable> deferred = new ArrayList<>();

        Run(String src) {
            this.src = src;
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < src.length(); i++) {
                if (src.charAt(i) == '\n') {
                    starts.add(i + 1);
                }
            }
            this.lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        }

        DdlParseResult execute() {
            List<Token> tokens = tokenize();
            for (List<Token> statement : splitStatements(tokens)) {
                try {
                    parseStatement(statement);
                } catch (SyntaxError error) {
                    warn(error.offset, error.getMessage());
                }
            }
            for (Runnable action : deferred) {
                action.run();
            }
            List<TableDef> result = new ArrayList<>();
            for (MutableTable table : tables) {
                result.add(table.freeze());
            }
            return new DdlParseResult(result, warnings);
        }

        private void warn(int offset, String message) {
            int index = Arrays.binarySearch(lineStarts, offset);
            int line = index >= 0 ? index : -index - 2;
            warnings.add(new DdlParseResult.Warning(line + 1, offset - lineStarts[line] + 1, message));
        }

        // ---------- 分词 ----------

        private List<Token> tokenize() {
            List<Token> tokens = new ArrayList<>();
            int length = src.length();
            int pos = 0;
            while (pos < length) {
                char c = src.charAt(pos);
                if (Character.isWhitespace(c)) {
                    pos++;
                } else if (c == '-' && peek(pos + 1) == '-') {
                    pos = skipLine(pos);
                } else if (c == '#') {
                    // MySQL 的行注释；PostgreSQL 里 # 是异或运算符，DDL 中基本不会出现
                    pos = skipLine(pos);
                } else if (c == '/' && peek(pos + 1) == '*') {
                    int close = src.indexOf("*/", pos + 2);
                    if (close < 0) {
                        warn(pos, "Unterminated block comment");
                        pos = length;
                    } else {
                        pos = close + 2;
                    }
                } else if (c == '\'') {
                    pos = readQuoted(tokens, pos, '\'', Kind.STRING, true);
                } else if (c == '"') {
                    pos = readQuoted(tokens, pos, '"', Kind.QUOTED, false);
                } else if (c == '`') {
                    pos = readQuoted(tokens, pos, '`', Kind.QUOTED, false);
                } else if (c == '[') {
                    pos = readBracket(tokens, pos);
                } else if (c == '$' && dollarTagEnd(pos) > 0) {
                    pos = readDollarQuoted(tokens, pos);
                } else if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(pos + 1)))) {
                    int end = pos;
                    while (end < length && (Character.isLetterOrDigit(src.charAt(end)) || src.charAt(end) == '.')) {
                        end++;
                    }
                    tokens.add(new Token(Kind.NUMBER, src.substring(pos, end), pos, end));
                    pos = end;
                } else if (Character.isLetter(c) || c == '_' || c == '@') {
                    int end = pos + 1;
                    while (end < length && isWordPart(src.charAt(end))) {
                        end++;
                    }
                    tokens.add(new Token(Kind.WORD, src.substring(pos, end), pos, end));
                    pos = end;
                } else if (c == ':' && peek(pos + 1) == ':') {
                    tokens.add(new Token(Kind.SYMBOL, "::", pos, pos + 2));
                    pos += 2;
                } else {
                    tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), pos, pos + 1));
                    pos++;
                }
            }
            return tokens;
        }

        private static boolean isWordPart(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '@';
        }

        private char peek(int index) {
            return index < src.length() ? src.charAt(index) : '\0';
        }

        private int skipLine(int pos) {
            int newline = src.indexOf('\n', pos);
            return newline < 0 ? src.length() : newline + 1;
        }

        /** 读取引号包围的内容：引号成对出现表示转义；字符串额外接受反斜杠转义 */
        private int readQuoted(List<Token> tokens, int start, char quote, Kind kind, boolean backslash) {
            StringBuilder value = new StringBuilder();
            int pos = start + 1;
            int length = src.length();
            while (pos < length) {
                char c = src.charAt(pos);
                if (backslash && c == '\\' && pos + 1 < length) {
                    value.append(unescape(src.charAt(pos + 1)));
                    pos += 2;
                } else if (c == quote) {
                    if (peek(pos + 1) == quote) {
                        value.append(quote);
                        pos += 2;
                    } else {
                        tokens.add(new Token(kind, value.toString(), start, pos + 1));
                        return pos + 1;
                    }
                } else {
                    value.append(c);
                    pos++;
                }
            }
            warn(start, kind == Kind.STRING ? "Unterminated string literal" : "Unterminated quoted identifier");
            tokens.add(new Token(kind, value.toString(), start, length));
            return length;
        }

        private static char unescape(char c) {
            switch (c) {
                case 'n':
                    return '\n';
                case 't':
                    return '\t';
                case 'r':
                    return '\r';
                case '0':
                    return '\0';
                default:
                    return c;
            }
        }

        /**
         * 方括号：SQL Server 的 {@code [标识符]}，或 PostgreSQL 数组标记 {@code []} / {@code [3]}。
         * 内容为空或全是数字时按数组标记处理。
         */
        private int readBracket(List<Token> tokens, int start) {
            int close = src.indexOf(']', start + 1);
            if (close < 0) {
                tokens.add(new Token(Kind.SYMBOL, "[", start, start + 1));
                return start + 1;
            }
            String inner = src.substring(start + 1, close);
            if (inner.isBlank() || inner.trim().chars().allMatch(Character::isDigit)) {
                tokens.add(new Token(Kind.SYMBOL, "[]", start, close + 1));
                return close + 1;
            }
            StringBuilder value = new StringBuilder();
            int pos = start + 1;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ']') {
                    if (peek(pos + 1) == ']') {
                        value.append(']');
                        pos += 2;
                        continue;
                    }
                    tokens.add(new Token(Kind.QUOTED, value.toString(), start, pos + 1));
                    return pos + 1;
                }
                value.append(c);
                pos++;
            }
            tokens.add(new Token(Kind.QUOTED, value.toString(), start, src.length()));
            return src.length();
        }

        /** {@code $tag$} 的结束位置（指向第二个 $ 之后）；不是合法标签时返回 -1 */
        private int dollarTagEnd(int start) {
            int pos = start + 1;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
            return pos < src.length() && src.charAt(pos) == '$' ? pos + 1 : -1;
        }

        /** PostgreSQL 的 $$...$$ / $tag$...$tag$ 字符串，多见于函数体，整体作为字符串跳过 */
        private int readDollarQuoted(List<Token> tokens, int start) {
            int tagEnd = dollarTagEnd(start);
            String tag = src.substring(start, tagEnd);
            int close = src.indexOf(tag, tagEnd);
            if (close < 0) {
                warn(start, "Unterminated dollar-quoted string");
                tokens.add(new Token(Kind.STRING, src.substring(tagEnd), start, src.length()));
                return src.length();
            }
            tokens.add(new Token(Kind.STRING, src.substring(tagEnd, close), start, close + tag.length()));
            return close + tag.length();
        }

        // ---------- 语句切分 ----------

        /**
         * 按分号切分语句；另外在遇到 CREATE、COMMENT ON 与独占的 GO 时也切开，
         * 这样漏写分号的语句只会连累自己，不会把下一张表吞掉。
         */
        private List<List<Token>> splitStatements(List<Token> tokens) {
            List<List<Token>> statements = new ArrayList<>();
            List<Token> current = new ArrayList<>();
            for (int i = 0; i < tokens.size(); i++) {
                Token token = tokens.get(i);
                boolean boundary = token.isSymbol(";") || (token.isWord("GO") && standsAlone(tokens, i));
                boolean startsNew = token.isWord("CREATE")
                        || (token.isWord("COMMENT") && i + 1 < tokens.size() && tokens.get(i + 1).isWord("ON"));
                if (boundary || (startsNew && !current.isEmpty())) {
                    if (!current.isEmpty()) {
                        statements.add(current);
                        current = new ArrayList<>();
                    }
                    if (boundary) {
                        continue;
                    }
                }
                current.add(token);
            }
            if (!current.isEmpty()) {
                statements.add(current);
            }
            return statements;
        }

        /** SQL Server 的批分隔符 GO 必须独占一行；否则可能只是一个叫 go 的列 */
        private boolean standsAlone(List<Token> tokens, int i) {
            int before = i == 0 ? 0 : tokens.get(i - 1).end();
            int after = i + 1 < tokens.size() ? tokens.get(i + 1).start() : src.length();
            Token go = tokens.get(i);
            return (i == 0 || src.substring(before, go.start()).indexOf('\n') >= 0)
                    && (i + 1 == tokens.size() || src.substring(go.end(), after).indexOf('\n') >= 0);
        }

        private void parseStatement(List<Token> st) {
            Token first = st.get(0);
            if (first.isWord("CREATE")) {
                int i = 1;
                while (i < st.size() && st.get(i).kind() == Kind.WORD
                        && CREATE_TABLE_MODIFIERS.contains(st.get(i).upper())) {
                    i++;
                }
                if (i < st.size() && st.get(i).isWord("TABLE")) {
                    parseCreateTable(st, i + 1);
                }
            } else if (first.isWord("COMMENT") && st.size() > 1 && st.get(1).isWord("ON")) {
                parseCommentOn(st);
            } else if (first.isWord("ALTER") && st.size() > 1 && st.get(1).isWord("TABLE")) {
                parseAlterTable(st);
            }
            // 其余语句（INSERT、DROP、SET、USE、CREATE INDEX/VIEW 等）与实体无关，直接跳过
        }

        // ---------- CREATE TABLE ----------

        private void parseCreateTable(List<Token> st, int start) {
            Cursor c = new Cursor(st, start);
            if (c.isWord("IF") && c.isWord(1, "NOT") && c.isWord(2, "EXISTS")) {
                c.advance(3);
            }
            QualifiedName name = qualifiedName(c, "table name");
            if (!c.isSymbol("(")) {
                if (c.hasMore() && (c.isWord("AS") || c.isWord("LIKE") || c.isWord("OF")
                        || c.isWord("PARTITION") || c.isWord("CLONE"))) {
                    throw new SyntaxError(c.peek().start(), "CREATE TABLE " + name.name + " "
                            + c.peek().upper() + " is not supported; only column definitions can be converted");
                }
                throw c.error("Expected '(' after table name " + name.name);
            }
            int open = c.index();
            int close = matchingParen(st, open);
            if (close < 0) {
                throw new SyntaxError(st.get(open).start(),
                        "Unclosed '(' in CREATE TABLE " + name.name);
            }

            MutableTable table = new MutableTable(name.schema, name.name);
            for (List<Token> element : splitTopLevel(st, open + 1, close)) {
                parseElement(table, element);
            }
            if (table.columns.isEmpty()) {
                throw new SyntaxError(st.get(open).start(), "Table " + name.name + " has no columns");
            }
            parseTableOptions(table, st, close + 1);
            table.applyPrimaryKey();

            for (int i = 0; i < tables.size(); i++) {
                if (tables.get(i).matches(name.schema, name.name)) {
                    warn(st.get(0).start(), "Table " + name.name + " is defined more than once; the last definition wins");
                    tables.remove(i);
                    break;
                }
            }
            tables.add(table);
        }

        private void parseElement(MutableTable table, List<Token> el) {
            if (el.isEmpty()) {
                return;
            }
            Token first = el.get(0);
            if (first.kind() == Kind.WORD) {
                String word = first.upper();
                boolean ambiguous = AMBIGUOUS_ELEMENT_WORDS.contains(word);
                boolean looksLikeColumn = ambiguous && el.size() > 1 && el.get(1).kind() == Kind.WORD
                        && JavaTypeMapper.isKnownType(el.get(1).text());
                if (TABLE_CONSTRAINT_WORDS.contains(word) || (ambiguous && !looksLikeColumn)) {
                    parseTableConstraint(table, el);
                    return;
                }
            }
            table.columns.add(parseColumn(table, el));
        }

        /** 表级约束只关心 PRIMARY KEY，其余（索引、外键、CHECK）与实体字段无关 */
        private void parseTableConstraint(MutableTable table, List<Token> el) {
            int i = 0;
            if (el.get(0).isWord("CONSTRAINT")) {
                i = el.size() > 1 && el.get(1).isWord("PRIMARY") ? 1 : 2;
            }
            if (i + 1 < el.size() && el.get(i).isWord("PRIMARY") && el.get(i + 1).isWord("KEY")) {
                List<String> columns = parenColumnList(el, i + 2);
                if (columns.isEmpty()) {
                    throw new SyntaxError(el.get(i).start(), "PRIMARY KEY of table " + table.name + " lists no columns");
                }
                table.primaryKey.clear();
                table.primaryKey.addAll(columns);
            }
        }

        /** 从 from 开始找到第一个括号组，取每一项的第一个标识符（忽略 ASC/DESC 与前缀长度） */
        private List<String> parenColumnList(List<Token> el, int from) {
            List<String> names = new ArrayList<>();
            int open = -1;
            for (int i = from; i < el.size(); i++) {
                if (el.get(i).isSymbol("(")) {
                    open = i;
                    break;
                }
            }
            if (open < 0) {
                return names;
            }
            int close = matchingParen(el, open);
            if (close < 0) {
                close = el.size();
            }
            for (List<Token> part : splitTopLevel(el, open + 1, close)) {
                for (Token token : part) {
                    if (token.isIdentifier()) {
                        names.add(token.text());
                        break;
                    }
                }
            }
            return names;
        }

        private ColumnDef parseColumn(MutableTable table, List<Token> el) {
            Token nameToken = el.get(0);
            if (!nameToken.isIdentifier()) {
                throw new SyntaxError(nameToken.start(), "Expected a column name in table " + table.name
                        + " but found '" + nameToken.text() + "'");
            }
            String name = nameToken.text();
            if (el.size() < 2 || !el.get(1).isIdentifier() || isColumnOptionStart(el, 1)) {
                throw new SyntaxError(nameToken.start(), "Column " + name + " in table " + table.name
                        + " has no data type");
            }
            MutableColumn col = new MutableColumn(name);
            int i = parseType(el, 1, col);
            parseColumnOptions(el, i, col);
            if ("serial".equals(col.typeName) || "bigserial".equals(col.typeName)
                    || "smallserial".equals(col.typeName) || col.typeName.startsWith("serial")) {
                col.autoIncrement = true;
                col.nullable = false;
            }
            if (col.primaryKey) {
                table.columnLevelKey.add(name);
            }
            return col.freeze();
        }

        /** 解析数据类型，返回类型之后第一个 token 的下标 */
        private int parseType(List<Token> el, int start, MutableColumn col) {
            int i = start;
            StringBuilder typeName = new StringBuilder(el.get(i).text().toLowerCase(Locale.ROOT));
            i++;
            // 模式限定的自定义类型：public.citext、"public"."mood"，只保留最后一段
            while (i + 1 < el.size() && el.get(i).isSymbol(".") && el.get(i + 1).isIdentifier()) {
                typeName.setLength(0);
                typeName.append(el.get(i + 1).text().toLowerCase(Locale.ROOT));
                i += 2;
            }
            while (i < el.size() && el.get(i).kind() == Kind.WORD) {
                Set<String> next = TYPE_CONTINUATIONS.get(typeName.toString());
                String word = el.get(i).text().toLowerCase(Locale.ROOT);
                if (next == null || !next.contains(word)) {
                    break;
                }
                typeName.append(' ').append(word);
                i++;
            }
            if ("interval".contentEquals(typeName)) {
                while (i < el.size() && el.get(i).kind() == Kind.WORD && INTERVAL_FIELDS.contains(el.get(i).upper())) {
                    i++;
                }
            }
            if (i < el.size() && el.get(i).isSymbol("(")) {
                int close = matchingParen(el, i);
                if (close < 0) {
                    throw new SyntaxError(el.get(i).start(), "Unclosed '(' in the type of column " + col.name);
                }
                List<Integer> numbers = new ArrayList<>();
                for (List<Token> part : splitTopLevel(el, i + 1, close)) {
                    Token value = part.isEmpty() ? null : part.get(0);
                    if (value == null) {
                        continue;
                    }
                    if (value.kind() == Kind.NUMBER) {
                        numbers.add(parseIntOrNull(value.text()));
                    } else if (value.kind() == Kind.STRING) {
                        col.enumValues.add(value.text());
                    } else if (value.isSymbol("*")) {
                        numbers.add(null);
                    }
                    // MAX、BYTE、CHAR 之类的单位与关键字不影响映射，忽略
                }
                col.length = numbers.isEmpty() ? null : numbers.get(0);
                col.scale = numbers.size() > 1 ? numbers.get(1) : null;
                i = close + 1;
            }
            String base = typeName.toString();
            if (("time".equals(base) || "timestamp".equals(base)) && i < el.size()) {
                if (el.get(i).isWord("WITH") && i + 2 < el.size() && el.get(i + 1).isWord("TIME")
                        && el.get(i + 2).isWord("ZONE")) {
                    typeName.append(" with time zone");
                    i += 3;
                } else if (el.get(i).isWord("WITH") && i + 3 < el.size() && el.get(i + 1).isWord("LOCAL")
                        && el.get(i + 2).isWord("TIME") && el.get(i + 3).isWord("ZONE")) {
                    typeName.append(" with local time zone");
                    i += 4;
                } else if (el.get(i).isWord("WITHOUT") && i + 2 < el.size() && el.get(i + 1).isWord("TIME")
                        && el.get(i + 2).isWord("ZONE")) {
                    typeName.append(" without time zone");
                    i += 3;
                }
            }
            while (i < el.size()) {
                Token token = el.get(i);
                if (token.isWord("UNSIGNED")) {
                    col.unsigned = true;
                    i++;
                } else if (token.isWord("SIGNED") || token.isWord("ZEROFILL")) {
                    i++;
                } else if (token.isSymbol("[]")) {
                    col.arrayDimensions++;
                    i++;
                } else if (token.isWord("ARRAY")) {
                    col.arrayDimensions++;
                    i++;
                    if (i < el.size() && el.get(i).isSymbol("[]")) {
                        i++;
                    }
                } else {
                    break;
                }
            }
            col.typeName = typeName.toString();
            col.declaredType = src.substring(el.get(start).start(), el.get(i - 1).end());
            return i;
        }

        private void parseColumnOptions(List<Token> el, int start, MutableColumn col) {
            int i = start;
            while (i < el.size()) {
                Token token = el.get(i);
                String word = token.kind() == Kind.WORD ? token.upper() : "";
                switch (word) {
                    case "NOT":
                        if (i + 1 < el.size() && el.get(i + 1).isWord("NULL")) {
                            col.nullable = false;
                        }
                        i += 2;
                        break;
                    case "NULL":
                        col.nullable = true;
                        i++;
                        break;
                    case "DEFAULT": {
                        int end = expressionEnd(el, i + 1);
                        if (end > i + 1) {
                            col.defaultValue = src.substring(el.get(i + 1).start(), el.get(end - 1).end());
                            if (col.defaultValue.toLowerCase(Locale.ROOT).startsWith("nextval")) {
                                col.autoIncrement = true;
                            }
                        }
                        i = end;
                        break;
                    }
                    case "AUTO_INCREMENT":
                    case "AUTOINCREMENT":
                        col.autoIncrement = true;
                        i++;
                        break;
                    case "IDENTITY":
                        col.autoIncrement = true;
                        i = skipParenGroup(el, i + 1);
                        break;
                    case "GENERATED":
                        i = parseGenerated(el, i + 1, col);
                        break;
                    case "AS":
                        // MySQL 的计算列：name INT AS (a + b) STORED
                        i = skipParenGroup(el, i + 1);
                        break;
                    case "PRIMARY":
                        col.primaryKey = true;
                        col.nullable = false;
                        i += i + 1 < el.size() && el.get(i + 1).isWord("KEY") ? 2 : 1;
                        break;
                    case "KEY":
                        // MySQL 列级的单独 KEY 就是 PRIMARY KEY
                        col.primaryKey = true;
                        col.nullable = false;
                        i++;
                        break;
                    case "UNIQUE":
                        col.unique = true;
                        i += i + 1 < el.size() && el.get(i + 1).isWord("KEY") ? 2 : 1;
                        break;
                    case "COMMENT": {
                        int value = i + 1;
                        if (value < el.size() && el.get(value).isSymbol("=")) {
                            value++;
                        }
                        if (value < el.size() && (el.get(value).kind() == Kind.STRING
                                || el.get(value).kind() == Kind.QUOTED)) {
                            col.comment = el.get(value).text();
                            i = value + 1;
                        } else {
                            i = value;
                        }
                        break;
                    }
                    case "CHARACTER":
                        i += i + 1 < el.size() && el.get(i + 1).isWord("SET") ? 3 : 1;
                        break;
                    case "CHARSET":
                    case "COLLATE":
                    case "CONSTRAINT":
                    case "SRID":
                    case "STORAGE":
                    case "COLUMN_FORMAT":
                        i += 2;
                        break;
                    case "ON":
                        i = skipReferentialAction(el, i + 1);
                        break;
                    case "REFERENCES":
                        i = skipReference(el, i + 1);
                        break;
                    case "CHECK":
                        i = skipParenGroup(el, i + 1);
                        break;
                    default:
                        // VISIBLE、STORED、ENABLE 等不影响实体的修饰，以及不认识的写法：跳过
                        i = token.isSymbol("(") ? skipParenGroup(el, i) : i + 1;
                        break;
                }
            }
        }

        /** GENERATED {ALWAYS | BY DEFAULT [ON NULL]} AS {IDENTITY [(...)] | (expr) [STORED]} */
        private int parseGenerated(List<Token> el, int start, MutableColumn col) {
            int i = start;
            while (i < el.size() && !el.get(i).isWord("AS")) {
                if (!(el.get(i).isWord("ALWAYS") || el.get(i).isWord("BY") || el.get(i).isWord("DEFAULT")
                        || el.get(i).isWord("ON") || el.get(i).isWord("NULL"))) {
                    return i;
                }
                i++;
            }
            i++;
            if (i < el.size() && el.get(i).isWord("IDENTITY")) {
                col.autoIncrement = true;
                return skipParenGroup(el, i + 1);
            }
            return skipParenGroup(el, i);
        }

        /** ON DELETE/UPDATE 之后的动作，或 MySQL 的 ON UPDATE CURRENT_TIMESTAMP */
        private int skipReferentialAction(List<Token> el, int start) {
            int i = start + 1; // 跳过 DELETE / UPDATE
            if (i >= el.size()) {
                return i;
            }
            Token action = el.get(i);
            if (action.isWord("CASCADE") || action.isWord("RESTRICT")) {
                return i + 1;
            }
            if (action.isWord("SET") || action.isWord("NO")) {
                return i + 2;
            }
            return expressionEnd(el, i);
        }

        /** REFERENCES t [(cols)] [MATCH x]；后续的 ON DELETE 由主循环处理 */
        private int skipReference(List<Token> el, int start) {
            int i = start;
            while (i < el.size() && (el.get(i).isIdentifier() || el.get(i).isSymbol("."))) {
                if (el.get(i).isWord("ON") || el.get(i).isWord("NOT") || el.get(i).isWord("NULL")) {
                    return i;
                }
                i++;
            }
            i = skipParenGroup(el, i);
            if (i < el.size() && el.get(i).isWord("MATCH")) {
                i += 2;
            }
            return i;
        }

        /** 若 i 处是左括号则跳到与之匹配的右括号之后，否则原样返回 */
        private int skipParenGroup(List<Token> el, int i) {
            if (i < el.size() && el.get(i).isSymbol("(")) {
                int close = matchingParen(el, i);
                return close < 0 ? el.size() : close + 1;
            }
            return i;
        }

        /**
         * DEFAULT 之后的表达式终点。
         *
         * <p>表达式没有显式终止符，只能按「一个项 + 若干后缀」来吞：项可以是字面量、标识符、
         * 函数调用、带前缀的字符串（{@code b'0'}、{@code N'x'}）或括号组；后缀是 {@code ::type}
         * 类型转换与二元运算符。遇到其他东西就停下，交还给列选项循环。</p>
         */
        private int expressionEnd(List<Token> el, int start) {
            int i = term(el, start);
            while (i < el.size()) {
                Token token = el.get(i);
                if (token.isSymbol("::")) {
                    i++;
                    while (i < el.size() && el.get(i).isIdentifier() && !isColumnOptionStart(el, i)) {
                        i++;
                    }
                    i = skipParenGroup(el, i);
                    while (i < el.size() && el.get(i).isSymbol("[]")) {
                        i++;
                    }
                } else if (token.kind() == Kind.SYMBOL && "+-*/%|.".contains(token.text()) && i + 1 < el.size()) {
                    i = token.isSymbol("|") && el.get(i + 1).isSymbol("|") ? term(el, i + 2) : term(el, i + 1);
                } else {
                    break;
                }
            }
            return i;
        }

        private int term(List<Token> el, int i) {
            if (i >= el.size()) {
                return i;
            }
            Token token = el.get(i);
            if (token.isSymbol("(")) {
                return skipParenGroup(el, i);
            }
            if (token.isSymbol("-") || token.isSymbol("+")) {
                return term(el, i + 1);
            }
            if (token.kind() == Kind.WORD) {
                int next = i + 1;
                if (next < el.size() && el.get(next).kind() == Kind.STRING && el.get(next).start() == token.end()) {
                    return next + 1;
                }
                return skipParenGroup(el, next);
            }
            return i + 1;
        }

        private boolean isColumnOptionStart(List<Token> el, int i) {
            Token token = el.get(i);
            if (token.kind() != Kind.WORD) {
                return false;
            }
            if (token.isWord("CHARACTER")) {
                return i + 1 < el.size() && el.get(i + 1).isWord("SET");
            }
            return COLUMN_OPTION_WORDS.contains(token.upper());
        }

        /** 右括号之后的表选项，只取表注释：COMMENT='x'、COMMENT 'x' */
        private void parseTableOptions(MutableTable table, List<Token> st, int start) {
            for (int i = start; i < st.size(); i++) {
                if (st.get(i).isWord("PARTITION")) {
                    // 分区定义里也能写 COMMENT，那是分区注释，不是表注释
                    return;
                }
                if (!st.get(i).isWord("COMMENT")) {
                    continue;
                }
                int value = i + 1;
                if (value < st.size() && st.get(value).isSymbol("=")) {
                    value++;
                }
                if (value < st.size() && (st.get(value).kind() == Kind.STRING || st.get(value).kind() == Kind.QUOTED)) {
                    table.comment = st.get(value).text();
                    i = value;
                }
            }
        }

        // ---------- COMMENT ON / ALTER TABLE ----------

        /** COMMENT ON TABLE t IS '...' / COMMENT ON COLUMN t.c IS '...'；其他对象（索引、序列）跳过 */
        private void parseCommentOn(List<Token> st) {
            Cursor c = new Cursor(st, 2);
            boolean isTable = c.isWord("TABLE");
            boolean isColumn = c.isWord("COLUMN");
            if (!isTable && !isColumn) {
                return;
            }
            c.advance(1);
            Token at = c.hasMore() ? c.peek() : st.get(0);
            List<String> parts = nameParts(c);
            if (!c.isWord("IS")) {
                throw c.error("Expected IS in COMMENT ON statement");
            }
            c.advance(1);
            if (!c.hasMore()) {
                throw c.error("Expected a string after IS");
            }
            Token value = c.peek();
            String comment = value.isWord("NULL") ? null : value.text();
            if (value.kind() != Kind.STRING && !value.isWord("NULL")) {
                throw new SyntaxError(value.start(), "Expected a string after IS but found '" + value.text() + "'");
            }
            if (isTable) {
                QualifiedName target = QualifiedName.of(parts);
                deferred.add(() -> {
                    MutableTable table = findTable(target);
                    if (table == null) {
                        warn(at.start(), "COMMENT ON TABLE refers to unknown table " + target.name);
                    } else {
                        table.comment = comment;
                    }
                });
            } else {
                if (parts.size() < 2) {
                    throw new SyntaxError(at.start(), "COMMENT ON COLUMN needs table.column");
                }
                String column = parts.get(parts.size() - 1);
                QualifiedName target = QualifiedName.of(parts.subList(0, parts.size() - 1));
                deferred.add(() -> {
                    MutableTable table = findTable(target);
                    int index = table == null ? -1 : table.columnIndex(column);
                    if (index < 0) {
                        warn(at.start(), "COMMENT ON COLUMN refers to unknown column " + target.name + "." + column);
                    } else {
                        table.columns.set(index, table.columns.get(index).withComment(comment));
                    }
                });
            }
        }

        /**
         * ALTER TABLE [ONLY] [IF EXISTS] t ADD [CONSTRAINT x] PRIMARY KEY (...)，
         * 以及 pg_dump 的 ALTER TABLE t ALTER COLUMN c SET DEFAULT nextval(...)（标记为自增）。
         * 其余 ALTER 一律忽略。
         */
        private void parseAlterTable(List<Token> st) {
            Cursor c = new Cursor(st, 2);
            if (c.isWord("ONLY")) {
                c.advance(1);
            }
            if (c.isWord("IF") && c.isWord(1, "EXISTS")) {
                c.advance(2);
            }
            if (!c.hasMore() || !c.peek().isIdentifier()) {
                return;
            }
            QualifiedName target = QualifiedName.of(nameParts(c));
            for (int i = c.index(); i + 1 < st.size(); i++) {
                if (st.get(i).isWord("PRIMARY") && st.get(i + 1).isWord("KEY")) {
                    List<String> columns = parenColumnList(st, i + 2);
                    if (!columns.isEmpty()) {
                        deferred.add(() -> {
                            MutableTable table = findTable(target);
                            if (table != null) {
                                table.primaryKey.clear();
                                table.primaryKey.addAll(columns);
                                table.applyPrimaryKey();
                            }
                        });
                    }
                    return;
                }
                if (st.get(i).isWord("COLUMN") && i + 4 < st.size() && st.get(i + 1).isIdentifier()
                        && st.get(i + 2).isWord("SET") && st.get(i + 3).isWord("DEFAULT")
                        && st.get(i + 4).isWord("nextval")) {
                    String column = st.get(i + 1).text();
                    deferred.add(() -> {
                        MutableTable table = findTable(target);
                        int index = table == null ? -1 : table.columnIndex(column);
                        if (index >= 0) {
                            table.markAutoIncrement(index);
                        }
                    });
                    return;
                }
            }
        }

        private MutableTable findTable(QualifiedName target) {
            for (MutableTable table : tables) {
                if (table.matches(target.schema, target.name)) {
                    return table;
                }
            }
            return null;
        }

        // ---------- 通用 ----------

        private QualifiedName qualifiedName(Cursor c, String what) {
            if (!c.hasMore() || !c.peek().isIdentifier()) {
                throw c.error("Expected " + what);
            }
            return QualifiedName.of(nameParts(c));
        }

        /** 读取 a.b.c 形式的名字各段 */
        private static List<String> nameParts(Cursor c) {
            List<String> parts = new ArrayList<>();
            while (c.hasMore() && c.peek().isIdentifier()) {
                parts.add(c.peek().text());
                c.advance(1);
                if (c.isSymbol(".") && c.hasMore(1) && c.peek(1).isIdentifier()) {
                    c.advance(1);
                } else {
                    break;
                }
            }
            return parts;
        }

        /** 与 open 处左括号匹配的右括号下标；没有则返回 -1 */
        private static int matchingParen(List<Token> tokens, int open) {
            int depth = 0;
            for (int i = open; i < tokens.size(); i++) {
                Token token = tokens.get(i);
                if (token.isSymbol("(")) {
                    depth++;
                } else if (token.isSymbol(")")) {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
            return -1;
        }

        /** 把 [from, to) 按顶层逗号切开 */
        private static List<List<Token>> splitTopLevel(List<Token> tokens, int from, int to) {
            List<List<Token>> parts = new ArrayList<>();
            List<Token> current = new ArrayList<>();
            int depth = 0;
            for (int i = from; i < to; i++) {
                Token token = tokens.get(i);
                if (token.isSymbol("(")) {
                    depth++;
                } else if (token.isSymbol(")")) {
                    depth--;
                } else if (token.isSymbol(",") && depth == 0) {
                    parts.add(current);
                    current = new ArrayList<>();
                    continue;
                }
                current.add(token);
            }
            parts.add(current);
            return parts;
        }

        private static Integer parseIntOrNull(String text) {
            try {
                return Integer.valueOf(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** 语句内的 token 游标 */
        private final class Cursor {
            private final List<Token> tokens;
            private int index;

            Cursor(List<Token> tokens, int index) {
                this.tokens = tokens;
                this.index = index;
            }

            int index() {
                return index;
            }

            boolean hasMore() {
                return index < tokens.size();
            }

            boolean hasMore(int ahead) {
                return index + ahead < tokens.size();
            }

            Token peek() {
                return tokens.get(index);
            }

            Token peek(int ahead) {
                return tokens.get(index + ahead);
            }

            void advance(int count) {
                index += count;
            }

            boolean isWord(String word) {
                return hasMore() && peek().isWord(word);
            }

            boolean isWord(int ahead, String word) {
                return hasMore(ahead) && peek(ahead).isWord(word);
            }

            boolean isSymbol(String symbol) {
                return hasMore() && peek().isSymbol(symbol);
            }

            SyntaxError error(String message) {
                if (hasMore()) {
                    return new SyntaxError(peek().start(), message + " but found '" + peek().text() + "'");
                }
                Token last = tokens.get(tokens.size() - 1);
                return new SyntaxError(last.end(), message + " but the statement ended");
            }
        }
    }

    // ==========================================
    // 解析期间的可变结构
    // ==========================================

    private record QualifiedName(String schema, String name) {
        /** db.t 取 db；db.dbo.t 取 dbo（SQL Server 的库.架构.表） */
        static QualifiedName of(List<String> parts) {
            if (parts.isEmpty()) {
                return new QualifiedName(null, "");
            }
            String name = parts.get(parts.size() - 1);
            String schema = parts.size() > 1 ? parts.get(parts.size() - 2) : null;
            return new QualifiedName(schema, name);
        }
    }

    private static final class MutableTable {
        final String schema;
        final String name;
        final List<ColumnDef> columns = new ArrayList<>();
        final List<String> primaryKey = new ArrayList<>();
        final List<String> columnLevelKey = new ArrayList<>();
        String comment;

        MutableTable(String schema, String name) {
            this.schema = schema;
            this.name = name;
        }

        boolean matches(String otherSchema, String otherName) {
            if (!name.equalsIgnoreCase(otherName)) {
                return false;
            }
            return schema == null || otherSchema == null || schema.equalsIgnoreCase(otherSchema);
        }

        int columnIndex(String columnName) {
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).name().equalsIgnoreCase(columnName)) {
                    return i;
                }
            }
            return -1;
        }

        /** 表级 PRIMARY KEY 优先；没有时用列级声明。随后把主键标志同步到各列 */
        void applyPrimaryKey() {
            if (primaryKey.isEmpty()) {
                primaryKey.addAll(columnLevelKey);
            }
            Map<String, String> byLower = new LinkedHashMap<>();
            for (String key : primaryKey) {
                byLower.put(key.toLowerCase(Locale.ROOT), key);
            }
            for (int i = 0; i < columns.size(); i++) {
                ColumnDef c = columns.get(i);
                boolean key = byLower.containsKey(c.name().toLowerCase(Locale.ROOT));
                if (key != c.primaryKey() || (key && c.nullable())) {
                    // 主键列隐含 NOT NULL
                    columns.set(i, new ColumnDef(c.name(), c.typeName(), c.declaredType(), c.length(), c.scale(),
                            c.enumValues(), c.unsigned(), c.arrayDimensions(), !key && c.nullable(), key,
                            c.autoIncrement(), c.unique(), c.defaultValue(), c.comment()));
                }
            }
        }

        void markAutoIncrement(int index) {
            ColumnDef c = columns.get(index);
            columns.set(index, new ColumnDef(c.name(), c.typeName(), c.declaredType(), c.length(), c.scale(),
                    c.enumValues(), c.unsigned(), c.arrayDimensions(), c.nullable(), c.primaryKey(),
                    true, c.unique(), c.defaultValue(), c.comment()));
        }

        TableDef freeze() {
            List<String> key = new ArrayList<>();
            for (String k : primaryKey) {
                int index = columnIndex(k);
                if (index >= 0) {
                    key.add(columns.get(index).name());
                }
            }
            return new TableDef(schema, name, columns, key, comment);
        }
    }

    private static final class MutableColumn {
        final String name;
        String typeName;
        String declaredType;
        Integer length;
        Integer scale;
        final List<String> enumValues = new ArrayList<>();
        boolean unsigned;
        int arrayDimensions;
        boolean nullable = true;
        boolean primaryKey;
        boolean autoIncrement;
        boolean unique;
        String defaultValue;
        String comment;

        MutableColumn(String name) {
            this.name = name;
        }

        ColumnDef freeze() {
            return new ColumnDef(name, typeName, declaredType, length, scale, enumValues, unsigned,
                    arrayDimensions, nullable && !primaryKey, primaryKey, autoIncrement, unique,
                    defaultValue, comment);
        }
    }
}
