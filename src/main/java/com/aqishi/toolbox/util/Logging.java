package com.aqishi.toolbox.util;

import com.aqishi.toolbox.vault.ApplicationPaths;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 日志目录引导。
 *
 * <p>logback 的配置文件是静态的，而日志落盘位置要跟随 {@link ApplicationPaths} 计算出的
 * 应用私有数据目录（Windows 走 %APPDATA%、macOS 走 Application Support、Linux 走 XDG），
 * 所以这里在 logback 初始化之前把目录算好、写进系统属性，由 {@code logback.xml} 引用。</p>
 *
 * <p><b>调用时机很关键</b>：logback 在第一次 {@code LoggerFactory.getLogger(...)} 时才读取
 * 配置，此后再改属性无效。因此 {@link #bootstrap()} 必须是 {@code Main.main} 的第一条语句，
 * 且本类自身不得持有 Logger 字段。</p>
 */
public final class Logging {

    /** logback.xml 中引用的属性名 */
    public static final String LOG_DIR_PROPERTY = "java.toolbox.logDir";

    private static volatile Path resolvedLogDirectory;

    private Logging() {
    }

    /**
     * 解析并创建日志目录，然后写入系统属性。
     *
     * <p>任何失败都不应阻止应用启动：目录不可用时退回 {@code java.io.tmpdir}，
     * 连它也不可用就让 logback 落到相对路径，控制台输出始终可用。</p>
     *
     * @return 最终生效的日志目录
     */
    public static Path bootstrap() {
        Path directory = resolveDirectory();
        resolvedLogDirectory = directory;
        System.setProperty(LOG_DIR_PROPERTY, directory.toString());
        return directory;
    }

    /** 已生效的日志目录；{@link #bootstrap()} 之前返回 null */
    public static Path logDirectory() {
        return resolvedLogDirectory;
    }

    private static Path resolveDirectory() {
        Path preferred = null;
        try {
            preferred = ApplicationPaths.systemDefault().getLogDirectory();
            Files.createDirectories(preferred);
            return preferred;
        } catch (Exception error) {
            // 引导期还没有日志可用，只能退化到 stderr，再继续尝试备选目录
            System.err.println("[toolbox] 无法创建日志目录 "
                    + (preferred == null ? "(未解析)" : preferred) + "：" + error);
        }

        try {
            Path fallback = Path.of(System.getProperty("java.io.tmpdir", "."), "java-toolbox-logs");
            Files.createDirectories(fallback);
            return fallback;
        } catch (Exception error) {
            System.err.println("[toolbox] 临时日志目录也不可用，日志将写入当前目录：" + error);
            return Path.of("logs");
        }
    }
}
