package com.aqishi.toolbox.feature.generation.domain;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * NanoID 生成器，算法与参考实现（ai/nanoid）一致。
 *
 * <p>随机字节先与掩码（不小于字母表长度的最小 2^n − 1）按位与，超出字母表的值直接丢弃重取。
 * 直接取模会让靠前的字符出现得更频繁，掩码 + 拒绝采样才能做到无偏。</p>
 */
public final class NanoIdGenerator {

    /**
     * 默认 URL 安全字母表（64 个字符）。参考实现的 urlAlphabet 是同一字符集的乱序排列
     * （为 gzip 压缩优化），字符集相同则生成结果的分布相同，这里按可读顺序书写。
     */
    public static final String DEFAULT_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
    public static final int DEFAULT_SIZE = 21;
    public static final int MAX_SIZE = 1024;
    public static final int MAX_ALPHABET = 256;

    private final Random random;

    public NanoIdGenerator() {
        this(new SecureRandom());
    }

    public NanoIdGenerator(Random random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public String generate() {
        return generate(DEFAULT_ALPHABET, DEFAULT_SIZE);
    }

    public String generate(String alphabet, int size) {
        validate(alphabet, size);
        int length = alphabet.length();
        int mask = (2 << (31 - Integer.numberOfLeadingZeros((length - 1) | 1))) - 1;
        // 参考实现的步长：1.6 是按平均拒绝率估出的余量，尽量一次取够随机字节
        int step = (int) Math.ceil(1.6 * mask * size / length);
        StringBuilder id = new StringBuilder(size);
        byte[] bytes = new byte[step];
        while (true) {
            random.nextBytes(bytes);
            for (int i = 0; i < step; i++) {
                int index = bytes[i] & mask;
                if (index < length) {
                    id.append(alphabet.charAt(index));
                    if (id.length() == size) {
                        return id.toString();
                    }
                }
            }
        }
    }

    /** 校验字母表（2..256 个互不相同的 BMP 字符）与长度（1..1024） */
    public static void validate(String alphabet, int size) {
        if (alphabet == null || alphabet.length() < 2 || alphabet.length() > MAX_ALPHABET) {
            throw new IdCodecException(IdCodecException.Code.INVALID_ALPHABET, "alphabet must have 2..256 chars");
        }
        Set<Character> seen = new HashSet<>();
        for (int i = 0; i < alphabet.length(); i++) {
            char c = alphabet.charAt(i);
            if (Character.isSurrogate(c) || !seen.add(c)) {
                throw new IdCodecException(IdCodecException.Code.INVALID_ALPHABET,
                        "alphabet has duplicate or unsupported characters");
            }
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IdCodecException(IdCodecException.Code.INVALID_SIZE, "size out of range", 1, MAX_SIZE);
        }
    }

    /** 熵（位）：size × log2(字母表长度) */
    public static double entropyBits(int alphabetLength, int size) {
        return size * (Math.log(alphabetLength) / Math.log(2));
    }
}
