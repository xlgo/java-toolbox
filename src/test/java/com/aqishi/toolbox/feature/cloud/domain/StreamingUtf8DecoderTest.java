package com.aqishi.toolbox.feature.cloud.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamingUtf8DecoderTest {

    /** 回归：中文字符的字节被拆在两帧里时，每帧单独解码会得到两个 U+FFFD。 */
    @Test
    void joinsCharactersSplitAcrossFrames() {
        byte[] bytes = "中文😀".getBytes(StandardCharsets.UTF_8);
        StreamingUtf8Decoder decoder = new StreamingUtf8Decoder();
        StringBuilder text = new StringBuilder();

        // 逐字节投喂：最极端的切分方式
        for (byte b : bytes) {
            text.append(decoder.decode(new byte[]{b}));
        }

        assertEquals("中文😀", text.toString());
    }

    @Test
    void emitsCompletePrefixImmediately() {
        byte[] bytes = "ab中".getBytes(StandardCharsets.UTF_8);
        StreamingUtf8Decoder decoder = new StreamingUtf8Decoder();

        assertEquals("ab", decoder.decode(Arrays.copyOf(bytes, 3)));
        assertEquals("中", decoder.decode(Arrays.copyOfRange(bytes, 3, bytes.length)));
    }

    @Test
    void replacesInvalidBytesWithoutThrowing() {
        StreamingUtf8Decoder decoder = new StreamingUtf8Decoder();

        assertEquals("a�b", decoder.decode(new byte[]{'a', (byte) 0xFF, 'b'}));
    }
}
