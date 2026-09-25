package com.aqishi.toolbox.feature.security.domain;

import java.util.List;

/**
 * 一次 JWK / JWKS 解析的结果。
 *
 * <p>单把坏掉的密钥不拖垮整份 JWKS：实际排查时经常是十把里有一把曲线不支持或编码有误，
 * 其余九把仍然要能看、能用来验签。失败的那把记在 {@link #failures()} 里。</p>
 *
 * @param keys          成功解析的密钥
 * @param failures      解析失败的条目
 * @param singleKey     输入是单个 JWK 对象而不是 {@code {"keys":[...]}}
 * @param duplicateKids 在集合中出现不止一次的 kid（按 kid 选钥时会产生歧义）
 */
public record JwkSet(List<JwkKey> keys, List<Failure> failures, boolean singleKey,
                     List<String> duplicateKids) {

    public JwkSet {
        keys = List.copyOf(keys);
        failures = List.copyOf(failures);
        duplicateKids = List.copyOf(duplicateKids);
    }

    /**
     * 一条解析失败。
     *
     * @param index 在 JWKS 中的下标
     * @param kid   能读出来的 kid，可能为 null
     * @param error 失败原因
     */
    public record Failure(int index, String kid, JwkException error) {
    }

    public static JwkSet empty() {
        return new JwkSet(List.of(), List.of(), false, List.of());
    }
}
