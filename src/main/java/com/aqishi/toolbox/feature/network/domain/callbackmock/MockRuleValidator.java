package com.aqishi.toolbox.feature.network.domain.callbackmock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Collects user-facing validation messages for rules and rule snapshots. */
public final class MockRuleValidator {
    private static final Pattern METHOD = Pattern.compile("[A-Za-z][A-Za-z0-9!#$%&'*+.^_`|~-]*");
    private static final Pattern JSON_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    public List<String> validate(MockRule rule) {
        List<String> errors = new ArrayList<String>();
        validateRule(rule, "", errors);
        return immutable(errors);
    }

    public List<String> validate(MockRuleSet ruleSet) {
        List<String> errors = new ArrayList<String>();
        if (ruleSet == null) {
            errors.add("ruleSet: must not be null");
            return immutable(errors);
        }
        List<MockRule> rules = ruleSet.getRules();
        if (rules != null) {
            for (int i = 0; i < rules.size(); i++) {
                validateRule(rules.get(i), "rules[" + i + "].", errors);
            }
        }
        validateResponse(ruleSet.getFallbackResponse(), "fallback.", errors);
        return immutable(errors);
    }

    public List<String> validate(MockResponse response) {
        List<String> errors = new ArrayList<String>();
        validateResponse(response, "", errors);
        return immutable(errors);
    }

    public List<String> validateRule(MockRule rule) {
        return validate(rule);
    }

    public List<String> validateRuleSet(MockRuleSet ruleSet) {
        return validate(ruleSet);
    }

    private void validateRule(MockRule rule, String prefix, List<String> errors) {
        if (rule == null) {
            errors.add(prefix + "rule: must not be null");
            return;
        }
        if (isBlank(rule.getName())) {
            errors.add(prefix + "name: must not be blank");
        }
        if (isBlank(rule.getMethod()) || !METHOD.matcher(rule.getMethod().trim()).matches()
                && !"ANY".equalsIgnoreCase(rule.getMethod().trim())) {
            errors.add(prefix + "method: must be ANY or a valid HTTP method");
        }
        if (rule.getPathMode() == null) {
            errors.add(prefix + "pathMode: must be selected");
        } else {
            validatePath(rule.getPathMode(), rule.getPath(), prefix + "path", errors);
        }
        List<MockCondition> conditions = rule.getConditions();
        if (conditions != null) {
            for (int i = 0; i < conditions.size(); i++) {
                validateCondition(conditions.get(i), prefix + "condition[" + i + ".", errors);
            }
        }
        validateResponse(rule.getResponse(), prefix + "response.", errors);
    }

    private void validatePath(PathMatchMode mode, String path, String field,
                              List<String> errors) {
        if (isBlank(path)) {
            errors.add(field + ": must not be blank");
            return;
        }
        if ((mode == PathMatchMode.EXACT || mode == PathMatchMode.PREFIX)
                && !path.startsWith("/")) {
            errors.add(field + ": exact and prefix paths must start with '/'");
        }
        if (mode == PathMatchMode.REGEX) {
            try {
                Pattern.compile(path);
            } catch (PatternSyntaxException e) {
                errors.add(field + ": invalid regular expression");
            }
        }
    }

    private void validateCondition(MockCondition condition, String prefix,
                                   List<String> errors) {
        if (condition == null) {
            errors.add(prefix + "condition: must not be null");
            return;
        }
        if (condition.getSource() == null) {
            errors.add(prefix + "source: must be selected");
        }
        if (isBlank(condition.getField())) {
            errors.add(prefix + "field: must not be blank");
        } else if (condition.getSource() == MatchSource.JSON
                && !isValidJsonPath(condition.getField())) {
            errors.add(prefix + "field: invalid JSON path");
        }
        if (condition.getOperator() == null) {
            errors.add(prefix + "operator: must be selected");
        } else if (condition.getOperator() == MatchOperator.EXISTS) {
            if (!isBlank(condition.getExpected())) {
                errors.add(prefix + "expected: must be empty for EXISTS");
            }
        } else {
            if (isBlank(condition.getExpected())) {
                errors.add(prefix + "expected: must not be blank");
            }
            if (condition.getOperator() == MatchOperator.REGEX
                    && !isBlank(condition.getExpected())) {
                try {
                    Pattern.compile(condition.getExpected());
                } catch (PatternSyntaxException e) {
                    errors.add(prefix + "expected: invalid regular expression");
                }
            }
        }
    }

    private void validateResponse(MockResponse response, String prefix,
                                  List<String> errors) {
        if (response == null) {
            errors.add(prefix + "response: must be provided");
            return;
        }
        if (response.getStatusCode() < 100 || response.getStatusCode() > 599) {
            errors.add(prefix + "statusCode: must be between 100 and 599");
        }
        if (isBlank(response.getContentType())) {
            errors.add(prefix + "contentType: must not be blank");
        }
        validateTemplate(response.getBody(), prefix + "template", errors);
    }

    private void validateTemplate(String body, String field, List<String> errors) {
        if (body == null) {
            return;
        }
        int cursor = 0;
        while (cursor < body.length()) {
            int start = body.indexOf("${", cursor);
            if (start < 0) {
                break;
            }
            int end = body.indexOf('}', start + 2);
            if (end < 0) {
                errors.add(field + ": unbalanced variable expression");
                return;
            }
            String expression = body.substring(start + 2, end);
            int dot = expression.indexOf('.');
            if (dot <= 0 || dot == expression.length() - 1) {
                errors.add(field + ": variable must use ${namespace.path}");
            } else {
                String namespace = expression.substring(0, dot);
                String path = expression.substring(dot + 1);
                if (!isNamespace(namespace)) {
                    errors.add(field + ": unknown variable namespace '" + namespace + "'");
                } else if ("json".equalsIgnoreCase(namespace)
                        && !isValidJsonPath(path)) {
                    errors.add(field + ": invalid JSON variable path");
                } else if (path.indexOf('{') >= 0 || path.indexOf('}') >= 0
                        || isBlank(path)) {
                    errors.add(field + ": invalid variable path");
                }
            }
            cursor = end + 1;
        }
    }

    private static boolean isNamespace(String namespace) {
        return "query".equalsIgnoreCase(namespace)
                || "header".equalsIgnoreCase(namespace)
                || "form".equalsIgnoreCase(namespace)
                || "json".equalsIgnoreCase(namespace);
    }

    /** Returns true for paths such as order.items[0].sku. */
    public static boolean isValidJsonPath(String path) {
        if (isBlank(path)) {
            return false;
        }
        int position = 0;
        boolean expectName = true;
        while (position < path.length()) {
            if (!expectName) {
                if (path.charAt(position) != '.') {
                    return false;
                }
                position++;
                if (position >= path.length()) {
                    return false;
                }
                expectName = true;
            }

            boolean hasName = false;
            if (path.charAt(position) != '[') {
                int start = position;
                while (position < path.length()
                        && path.charAt(position) != '.'
                        && path.charAt(position) != '[') {
                    position++;
                }
                if (start == position
                        || !JSON_NAME.matcher(path.substring(start, position)).matches()) {
                    return false;
                }
                hasName = true;
            }
            int indexCount = 0;
            while (position < path.length() && path.charAt(position) == '[') {
                int close = path.indexOf(']', position + 1);
                if (close < 0 || close == position + 1) {
                    return false;
                }
                String index = path.substring(position + 1, close);
                for (int i = 0; i < index.length(); i++) {
                    if (!Character.isDigit(index.charAt(i))) {
                        return false;
                    }
                }
                position = close + 1;
                indexCount++;
            }
            if (!hasName && indexCount == 0) {
                return false;
            }
            if (position < path.length() && path.charAt(position) != '.') {
                return false;
            }
            expectName = false;
        }
        return !expectName;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().length() == 0;
    }

    private static List<String> immutable(List<String> errors) {
        return Collections.unmodifiableList(new ArrayList<String>(errors));
    }
}
