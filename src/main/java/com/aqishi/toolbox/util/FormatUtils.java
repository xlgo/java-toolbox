package com.aqishi.toolbox.util;

/**
 * 界面上反复用到的数值格式化。
 *
 * <p>字节大小此前有四份各自的实现，精度、单位上限、空格都不一致：
 * 状态栏显示 "1.5GB"，文件传输显示 "1.50 MB"，SFTP 又用另一套单位表。
 * 统一到这里，避免出现同一个文件在两个面板里显示成两种大小。</p>
 */
public final class FormatUtils {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

    private FormatUtils() {
    }

    /**
     * 把字节数格式化为带单位的文本，如 {@code 1.5 GB}。
     *
     * <p>小于 1KB 时输出整数，其余保留一位小数。单位可一直延伸到 PB，
     * 大文件不会退化成 "1024.0 MB"。</p>
     */
    public static String bytes(long size) {
        if (size < 1024L) {
            return size + " B";
        }
        int unit = (63 - Long.numberOfLeadingZeros(size)) / 10;
        if (unit >= UNITS.length) {
            unit = UNITS.length - 1;
        }
        return String.format("%.1f %s", (double) size / (1L << (unit * 10)), UNITS[unit]);
    }

    /** 把毫秒格式化为易读时长，如 {@code 2 分 05 秒}、{@code 1.8 秒}。 */
    public static String duration(long millis) {
        if (millis < 0) {
            return "-";
        }
        if (millis < 1000L) {
            return millis + " 毫秒";
        }
        if (millis < 60_000L) {
            return String.format("%.1f 秒", millis / 1000.0);
        }
        long seconds = millis / 1000L;
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        if (hours > 0) {
            return String.format("%d 小时 %02d 分", hours, minutes % 60L);
        }
        return String.format("%d 分 %02d 秒", minutes, seconds % 60L);
    }
}
