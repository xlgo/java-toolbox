package com.aqishi.toolbox.feature.security.domain;

import java.util.List;

/**
 * JWK / JWT 处理中的可预期失败。
 *
 * <p>{@link #getCode()} 是稳定的错误码，界面层据此拼出本地化键；{@link #getParams()} 是填进文案的参数
 * （成员名、期望长度等），只包含公开信息，绝不携带私钥材料。异常 message 是英文，只给日志看。</p>
 */
public final class JwkException extends RuntimeException {

    private final String code;
    private final List<Object> params;

    public JwkException(String code, Object... params) {
        super(code + (params.length == 0 ? "" : " " + List.of(params)));
        this.code = code;
        this.params = List.of(params);
    }

    public String getCode() {
        return code;
    }

    public List<Object> getParams() {
        return params;
    }
}
