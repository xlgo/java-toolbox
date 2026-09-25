package com.aqishi.toolbox.feature.security.domain;

import java.time.Instant;
import java.util.List;

/**
 * JWT 验签结果：结论 + 逐项检查。
 *
 * <p>每条检查只给出稳定的 {@code code} 和结构化参数（字符串、数字、{@link Instant}），
 * 文案由界面层按 code 本地化；时间用 Instant 交出去，由界面决定按哪个时区显示。</p>
 *
 * @param verdict     总体结论
 * @param algorithm   令牌头里的 alg 原文，可能为 null
 * @param headerKid   令牌头里的 kid，可能为 null
 * @param key         实际用于验签的密钥，未选出时为 null
 * @param checks      按执行顺序排列的检查
 * @param headerJson  美化后的头部 JSON，无法解码时为 null
 * @param payloadJson 美化后的载荷 JSON，无法解码时为 null
 * @param now         判定时刻（来自注入的时钟）
 */
public record JwtVerification(Verdict verdict, String algorithm, String headerKid, JwkKey key,
                              List<Check> checks, String headerJson, String payloadJson, Instant now) {

    public JwtVerification {
        checks = List.copyOf(checks);
    }

    /** 总体结论。 */
    public enum Verdict {
        /** 签名有效，所有声明检查通过。 */
        VALID,
        /** 签名无效、算法被拒绝或某项声明检查失败。 */
        INVALID,
        /** 结构正常，但找不到可用的验签密钥（kid 不存在、有歧义、没有兼容的密钥）。 */
        UNVERIFIABLE,
        /** 不是合法的紧凑 JWS。 */
        MALFORMED
    }

    /** 单项检查的状态。 */
    public enum Status {
        PASS, FAIL, WARN, SKIP
    }

    /** 检查项。 */
    public enum CheckId {
        STRUCTURE, ALGORITHM, CRIT, KEY, SIGNATURE, EXP, NBF, IAT, ISS, AUD
    }

    /**
     * 一项检查。
     *
     * @param id     检查项
     * @param status 状态
     * @param code   稳定的结果码，例如 {@code sig.valid}
     * @param params 填进文案的参数
     */
    public record Check(CheckId id, Status status, String code, List<Object> params) {
        public Check {
            params = List.copyOf(params);
        }
    }

    /** 找出某项检查（同一项可能有多条时返回第一条）。 */
    public Check check(CheckId id) {
        for (Check check : checks) {
            if (check.id() == id) {
                return check;
            }
        }
        return null;
    }

    public boolean isValid() {
        return verdict == Verdict.VALID;
    }
}
