package com.aqishi.toolbox.feature.system.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 常见已知依赖冲突规则契约。
 */
public record KnownConflictRule(
        String id,
        Pattern firstPattern,
        Pattern secondPattern,
        String categoryKey,
        String descriptionKey,
        String suggestionKey
) {
    public KnownConflictRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(firstPattern, "firstPattern");
        Objects.requireNonNull(secondPattern, "secondPattern");
        Objects.requireNonNull(categoryKey, "categoryKey");
        Objects.requireNonNull(descriptionKey, "descriptionKey");
        Objects.requireNonNull(suggestionKey, "suggestionKey");
    }

    public boolean matches(String pathA, String pathB) {
        if (firstPattern.matcher(pathA).find() && secondPattern.matcher(pathB).find()) {
            return true;
        }
        return firstPattern.matcher(pathB).find() && secondPattern.matcher(pathA).find();
    }
}
