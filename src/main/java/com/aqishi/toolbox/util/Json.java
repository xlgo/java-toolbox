package com.aqishi.toolbox.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 进程级共享的 Jackson {@link ObjectMapper} 实例。
 * <p>ObjectMapper 在完成配置后是线程安全的，读取/写入可并发调用；
 * 需要修改配置的场景请自建实例，不要改动这里的共享配置。</p>
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper PRETTY_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private Json() {
    }

    /** 共享的默认 mapper（紧凑输出）。 */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** 共享的缩进美化 mapper（INDENT_OUTPUT）。 */
    public static ObjectMapper prettyMapper() {
        return PRETTY_MAPPER;
    }
}
