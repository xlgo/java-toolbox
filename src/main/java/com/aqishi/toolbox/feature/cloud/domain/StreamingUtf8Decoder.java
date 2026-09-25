package com.aqishi.toolbox.feature.cloud.domain;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 把分帧到达的字节流解码成文本，跨帧的多字节字符不会被切坏。
 *
 * <p>exec 通道的 WebSocket 帧边界与字符边界无关：一个中文字符的三个字节可能分在两帧里。
 * 每帧各自 {@code new String(bytes, UTF_8)} 会把两半都变成 U+FFFD。这里保留上一帧末尾
 * 不完整的字节，与下一帧拼起来再解码。非法字节仍替换为 U+FFFD，不会抛异常。</p>
 *
 * <p>不是线程安全的；每个输出通道各用一个实例，只在接收线程上调用。</p>
 */
public final class StreamingUtf8Decoder {

    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    /** 上一帧末尾尚未凑成完整字符的字节（UTF-8 最多 3 个）。 */
    private ByteBuffer pending = ByteBuffer.allocate(0);

    /** 解码一帧，返回这一帧能确定下来的全部文本。 */
    public String decode(byte[] chunk) {
        ByteBuffer input = ByteBuffer.allocate(pending.remaining() + chunk.length);
        input.put(pending).put(chunk).flip();
        CharBuffer output = CharBuffer.allocate(input.remaining() + 1);
        decoder.decode(input, output, false);
        pending = input.slice();
        output.flip();
        return output.toString();
    }
}
