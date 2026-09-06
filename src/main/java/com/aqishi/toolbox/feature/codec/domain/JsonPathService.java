package com.aqishi.toolbox.feature.codec.domain;

import com.aqishi.toolbox.util.Json;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.ParseContext;
import com.jayway.jsonpath.PathNotFoundException;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * JSONPath 提取与评估领域服务。
 * <p>支持基于 JSONPath 表达式的值查询、切片筛选、谓词过滤以及路径列表追踪。</p>
 */
public class JsonPathService {

    public static final String SAMPLE_JSON = "{\n"
            + "  \"store\": {\n"
            + "    \"book\": [\n"
            + "      {\n"
            + "        \"category\": \"reference\",\n"
            + "        \"author\": \"Nigel Rees\",\n"
            + "        \"title\": \"Sayings of the Century\",\n"
            + "        \"price\": 8.95\n"
            + "      },\n"
            + "      {\n"
            + "        \"category\": \"fiction\",\n"
            + "        \"author\": \"Evelyn Waugh\",\n"
            + "        \"title\": \"Sword of Honour\",\n"
            + "        \"price\": 12.99\n"
            + "      },\n"
            + "      {\n"
            + "        \"category\": \"fiction\",\n"
            + "        \"author\": \"Herman Melville\",\n"
            + "        \"title\": \"Moby Dick\",\n"
            + "        \"isbn\": \"0-553-21311-3\",\n"
            + "        \"price\": 8.99\n"
            + "      },\n"
            + "      {\n"
            + "        \"category\": \"fiction\",\n"
            + "        \"author\": \"J. R. R. Tolkien\",\n"
            + "        \"title\": \"The Lord of the Rings\",\n"
            + "        \"isbn\": \"0-395-19395-8\",\n"
            + "        \"price\": 22.99\n"
            + "      }\n"
            + "    ],\n"
            + "    \"bicycle\": {\n"
            + "      \"color\": \"red\",\n"
            + "      \"price\": 19.95\n"
            + "    }\n"
            + "  },\n"
            + "  \"expensive\": 10\n"
            + "}";

    private static final Map<String, String> COMMON_EXPRESSIONS;

    static {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("$.store.book[*].author", "所有图书的作者 ($.store.book[*].author)");
        map.put("$..author", "递归查找所有作者 ($..author)");
        map.put("$.store.book[?(@.price < 10)]", "条件过滤：价格小于 10 的图书");
        map.put("$.store.book[0:2]", "切片：获取前 2 本图书 ($.store.book[0:2])");
        map.put("$.store.book[-1:]", "切片：获取最后一本图书 ($.store.book[-1:])");
        map.put("$..book[?(@.isbn)]", "属性存在过滤：包含 isbn 的图书");
        map.put("$.store..price", "递归查找所有价格 ($.store..price)");
        map.put("$.store.book.length()", "聚合函数：图书列表长度");
        COMMON_EXPRESSIONS = Collections.unmodifiableMap(map);
    }

    public static Map<String, String> getCommonExpressions() {
        return COMMON_EXPRESSIONS;
    }

    /**
     * 执行 JSONPath 评估。
     *
     * @param json           原始 JSON 字符串
     * @param pathExpression JSONPath 表达式（如 $.store.book[*].author）
     * @param asPathList     是否仅返回命中节点的路径列表（而非值）
     * @param pretty         输出结果是否格式化缩进
     * @return 评估结果
     */
    public EvaluationResult evaluate(String json, String pathExpression, boolean asPathList, boolean pretty) {
        if (json == null || json.trim().isEmpty()) {
            return EvaluationResult.failure("JSON 文本不能为空", 0);
        }
        if (pathExpression == null || pathExpression.trim().isEmpty()) {
            return EvaluationResult.failure("JSONPath 表达式不能为空", 0);
        }

        long start = System.currentTimeMillis();
        try {
            Set<Option> options = EnumSet.noneOf(Option.class);
            options.add(Option.DEFAULT_PATH_LEAF_TO_NULL);
            if (asPathList) {
                options.add(Option.AS_PATH_LIST);
            }

            Configuration configuration = Configuration.builder()
                    .options(options)
                    .build();

            ParseContext parseContext = JsonPath.using(configuration);
            Object result = parseContext.parse(json.trim()).read(pathExpression.trim());

            int count = calculateMatchCount(result);
            String formattedOutput = formatResult(result, pretty);
            long elapsed = System.currentTimeMillis() - start;
            return EvaluationResult.success(formattedOutput, count, elapsed);
        } catch (PathNotFoundException e) {
            long elapsed = System.currentTimeMillis() - start;
            return EvaluationResult.success(asPathList ? "[]" : "null", 0, elapsed);
        } catch (InvalidJsonException e) {
            long elapsed = System.currentTimeMillis() - start;
            return EvaluationResult.failure("JSON 格式错误: " + extractRootMessage(e), elapsed);
        } catch (InvalidPathException e) {
            long elapsed = System.currentTimeMillis() - start;
            return EvaluationResult.failure("JSONPath 语法错误: " + extractRootMessage(e), elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return EvaluationResult.failure("执行异常: " + extractRootMessage(e), elapsed);
        }
    }

    private int calculateMatchCount(Object result) {
        if (result == null) {
            return 0;
        }
        if (result instanceof Collection<?> col) {
            return col.size();
        }
        return 1;
    }

    private String formatResult(Object result, boolean pretty) {
        if (result == null) {
            return "null";
        }
        try {
            return pretty
                    ? Json.prettyMapper().writeValueAsString(result)
                    : Json.mapper().writeValueAsString(result);
        } catch (Exception e) {
            return String.valueOf(result);
        }
    }

    private String extractRootMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return msg.trim();
    }

    /**
     * 评估结果模型
     */
    public static class EvaluationResult {
        private final boolean success;
        private final String output;
        private final int matchCount;
        private final long elapsedMs;
        private final String errorMessage;

        private EvaluationResult(boolean success, String output, int matchCount, long elapsedMs, String errorMessage) {
            this.success = success;
            this.output = output;
            this.matchCount = matchCount;
            this.elapsedMs = elapsedMs;
            this.errorMessage = errorMessage;
        }

        public static EvaluationResult success(String output, int matchCount, long elapsedMs) {
            return new EvaluationResult(true, output, matchCount, elapsedMs, null);
        }

        public static EvaluationResult failure(String errorMessage, long elapsedMs) {
            return new EvaluationResult(false, null, 0, elapsedMs, errorMessage);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getOutput() {
            return output;
        }

        public int getMatchCount() {
            return matchCount;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
