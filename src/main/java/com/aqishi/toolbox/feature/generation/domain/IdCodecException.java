package com.aqishi.toolbox.feature.generation.domain;

import java.util.Arrays;
import java.util.Objects;

/**
 * 分布式 ID 编解码与生成过程中的失败。
 *
 * <p>领域层不产出面向用户的中文：失败原因用 {@link Code} 表达，界面据此映射到
 * 国际化键，{@link #getArgs()} 作为 MessageFormat 参数；异常消息本身是英文，只给日志看。</p>
 */
public final class IdCodecException extends RuntimeException {

    /** 失败原因代码 */
    public enum Code {
        /** 输入为空 */
        EMPTY,
        /** 长度不符合该格式，参数：期望长度、实际长度 */
        INVALID_LENGTH,
        /** 含非法字符，参数：字符、位置（从 0 起） */
        INVALID_CHARACTER,
        /** 数值超出该格式可表示的范围 */
        OVERFLOW,
        /** 字段取值越界，参数：字段名、最大值 */
        OUT_OF_RANGE,
        /** 布局中不存在的字段，参数：字段名 */
        UNKNOWN_FIELD,
        /** 时间早于布局纪元 */
        BEFORE_EPOCH,
        /** 时间戳位已用尽（晚于该布局能表示的最大时间），参数：溢出时刻 ISO */
        TIMESTAMP_OVERFLOW,
        /** 时钟回拨超过容忍上限，参数：回拨毫秒数 */
        CLOCK_BACKWARDS,
        /** 单调模式下同一毫秒内的随机部分已加到上限 */
        MONOTONIC_OVERFLOW,
        /** 位布局非法（总位数超 63、字段重名等），参数：英文说明 */
        INVALID_LAYOUT,
        /** NanoID 字母表非法（为空、过长或有重复字符） */
        INVALID_ALPHABET,
        /** 长度 / 数量参数越界，参数：最小值、最大值 */
        INVALID_SIZE,
        /** 无法解析的时间文本 */
        INVALID_TIME
    }

    private final Code code;
    private final Object[] args;

    public IdCodecException(Code code, String message, Object... args) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.args = args == null ? new Object[0] : args.clone();
    }

    public Code getCode() {
        return code;
    }

    public Object[] getArgs() {
        return args.clone();
    }

    @Override
    public String toString() {
        return "IdCodecException[" + code + "] " + getMessage() + " " + Arrays.toString(args);
    }
}
