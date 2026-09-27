package com.aqishi.toolbox.feature.system.infra;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 子进程输出的字符集判定与解码。
 *
 * <p>Windows 的 netstat、tasklist 往管道里写的是<em>控制台（OEM）代码页</em>，不是 Java 的
 * {@code native.encoding}（ANSI 代码页）：中文系统两者恰好都是 936（GBK），西欧系统却是
 * 850 对 1252，德文 netstat 的「ABHÖREN」按 1252 解码就成了乱码。所以判定顺序是：</p>
 * <ol>
 *     <li>子进程里执行 {@code chcp} 得到的代码页——它和随后启动的 netstat 处在同一种控制台环境，最可信；</li>
 *     <li>{@code stdout.encoding} / {@code sun.stdout.encoding}：JVM 自己挂在控制台上时才有值，就是控制台代码页；</li>
 *     <li>{@code native.encoding}，再退到 {@link Charset#defaultCharset()}。</li>
 * </ol>
 * <p>解码时先按严格 UTF-8 试一次：用户执行过 {@code chcp 65001}，或 PowerShell 被要求输出 UTF-8 时，
 * 字节流是合法 UTF-8；而 GBK 等双字节编码的中文几乎不可能恰好构成合法 UTF-8。wmic 往管道写 UTF-16LE，
 * 也在这里识别。所有判定都是纯函数，测试可直接喂字节。</p>
 */
public final class ConsoleCharsets {

    private static final char BOM = (char) 0xFEFF;
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d{3,5})\\D*$");

    private ConsoleCharsets() {
    }

    /** 从 {@code chcp} 的输出（例如「Active code page: 936」或其本地化版本）取代码页编号。 */
    public static OptionalInt parseCodePage(String chcpOutput) {
        if (chcpOutput == null) {
            return OptionalInt.empty();
        }
        Matcher matcher = TRAILING_NUMBER.matcher(chcpOutput.trim());
        if (!matcher.find()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Integer.parseInt(matcher.group(1)));
    }

    /** Windows 代码页编号 → Java 字符集；本 JDK 不支持时返回 null。 */
    public static Charset forCodePage(int codePage) {
        switch (codePage) {
            case 65001:
                return StandardCharsets.UTF_8;
            case 936:
                return lookup("GBK");
            case 54936:
                return lookup("GB18030");
            case 950:
                return lookup("Big5");
            case 932:
                return lookup("windows-31j", "Shift_JIS");
            case 949:
                return lookup("x-windows-949", "EUC-KR");
            case 1200:
                return StandardCharsets.UTF_16LE;
            default:
                return lookup("IBM" + codePage, "x-IBM" + codePage, "cp" + codePage,
                        "windows-" + codePage, "x-windows-" + codePage);
        }
    }

    /**
     * 不执行 chcp 时的退路：按系统属性推断控制台字符集。
     *
     * @param properties 属性读取函数，测试时可替换
     */
    public static Charset fromProperties(UnaryOperator<String> properties) {
        for (String key : List.of("stdout.encoding", "sun.stdout.encoding", "native.encoding")) {
            String value = properties.apply(key);
            if (value != null && !value.isBlank()) {
                Charset charset = lookup(value.trim());
                if (charset != null) {
                    return charset;
                }
            }
        }
        return Charset.defaultCharset();
    }

    /**
     * 解码命令输出：UTF-16LE（带 BOM 或明显的零字节交错）→ 严格 UTF-8 → 控制台字符集。
     */
    public static String decode(byte[] bytes, Charset consoleCharset) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        if (looksUtf16Le(bytes)) {
            int offset = (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) ? 2 : 0;
            return new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_16LE);
        }
        String utf8 = strictUtf8(bytes);
        if (utf8 != null) {
            return !utf8.isEmpty() && utf8.charAt(0) == BOM ? utf8.substring(1) : utf8;
        }
        Charset charset = consoleCharset != null ? consoleCharset : Charset.defaultCharset();
        return new String(bytes, charset);
    }

    private static boolean looksUtf16Le(byte[] bytes) {
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
            return true;
        }
        // 开头若干个 ASCII 字符的高字节全是 0：wmic 的输出总以列名或空白开头。
        if (bytes.length < 8 || bytes.length % 2 != 0) {
            return false;
        }
        for (int i = 1; i < 8; i += 2) {
            if (bytes[i] != 0 || bytes[i - 1] == 0) {
                return false;
            }
        }
        return true;
    }

    private static String strictUtf8(byte[] bytes) {
        try {
            CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return chars.toString();
        } catch (CharacterCodingException error) {
            return null;
        }
    }

    private static Charset lookup(String... names) {
        for (String name : names) {
            try {
                if (Charset.isSupported(name)) {
                    return Charset.forName(name);
                }
            } catch (IllegalArgumentException ignored) {
                // 非法字符集名：换下一个候选。
            }
        }
        return null;
    }
}
