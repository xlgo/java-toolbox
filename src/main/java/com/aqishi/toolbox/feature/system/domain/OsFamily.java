package com.aqishi.toolbox.feature.system.domain;

import java.util.Locale;

/**
 * 端口/进程工具关心的操作系统族：决定用哪套命令、PID 保护范围和结束进程的方式。
 */
public enum OsFamily {
    WINDOWS, MAC, LINUX, OTHER_UNIX;

    /** 按 {@code os.name} 判定；抽成纯函数以便测试注入任意系统名。 */
    public static OsFamily detect(String osName) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) {
            return WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return MAC;
        }
        if (name.contains("linux")) {
            return LINUX;
        }
        return OTHER_UNIX;
    }

    public static OsFamily current() {
        return detect(System.getProperty("os.name"));
    }

    public boolean isWindows() {
        return this == WINDOWS;
    }
}
