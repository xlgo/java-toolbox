package com.aqishi.toolbox.feature.system.domain;

import java.io.IOException;
import java.util.Objects;

/**
 * class 文件格式错误：携带错误码与出错的字节偏移。
 *
 * <p>消息只写英文，界面按 {@link #code()} 映射到本地化文案；偏移量让用户能拿十六进制编辑器
 * 直接定位到损坏处（常见于 FTP 文本模式传输、被截断的下载、被加密壳处理过的 class）。</p>
 *
 * <p>继承 {@link IOException}，读文件的调用方一个 catch 就能同时处理 I/O 与格式错误。</p>
 */
public final class ClassFileException extends IOException {

    /** 错误分类；界面据此选择文案，不依赖英文消息。 */
    public enum Code {
        /** 读到声明的结构之前数据就结束了 */
        TRUNCATED,
        /** 开头不是 0xCAFEBABE */
        BAD_MAGIC,
        /** 主版本号小于 45（不存在这样的 Java 版本） */
        BAD_VERSION,
        /** 常量池里出现未知的 tag */
        BAD_CONSTANT_TAG,
        /** 常量池下标越界、指向 long/double 的第二个槽位，或类型不符 */
        BAD_CONSTANT_INDEX,
        /** 修改版 UTF-8 编码非法 */
        BAD_UTF8,
        /** 描述符或属性内容与声明不一致 */
        BAD_ATTRIBUTE,
        /** 计数值大得离谱（剩余字节根本放不下），疑似构造的恶意输入 */
        LIMIT_EXCEEDED,
        /** 输入超过允许解析的最大字节数 */
        TOO_LARGE
    }

    private final Code code;
    private final long offset;

    public ClassFileException(Code code, long offset, String message) {
        super(message + " (offset " + offset + ")");
        this.code = Objects.requireNonNull(code, "code");
        this.offset = offset;
    }

    public Code code() {
        return code;
    }

    /** 出错位置相对 class 文件开头的字节偏移。 */
    public long offset() {
        return offset;
    }
}
