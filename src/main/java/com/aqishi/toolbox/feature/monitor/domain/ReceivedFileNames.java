package com.aqishi.toolbox.feature.monitor.domain;

import java.io.File;
import java.util.Locale;
import java.util.Set;

/**
 * 远程对端发来的文件名只能当作"建议"。
 *
 * <p>被控端会自动接收文件并落盘到下载目录；早先直接用对端给的名字拼路径，
 * {@code ../../AppData/...} 就能把文件写到下载目录以外的任意位置，且会覆盖已有文件。
 * 这里只保留最后一段、替换掉各平台的非法字符，并在重名时追加编号。</p>
 */
public final class ReceivedFileNames {

    /** Windows 保留的设备名，作为文件名（不论扩展名）会指向设备而不是文件。 */
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private static final int MAX_LENGTH = 200;

    private ReceivedFileNames() {
    }

    /** 把对端提供的名字收敛成一个安全的单段文件名；无法得到有效名字时返回 {@code "received-file"}。 */
    public static String sanitize(String proposed) {
        String name = proposed == null ? "" : proposed;
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(cut + 1);
        StringBuilder clean = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            clean.append(ch < 0x20 || "<>:\"|?*".indexOf(ch) >= 0 ? '_' : ch);
        }
        // Windows 会悄悄去掉结尾的点和空格，"a.txt." 与 "a.txt" 是同一个文件。
        String result = clean.toString().trim().replaceAll("[. ]+$", "");
        while (result.startsWith(".")) {
            result = result.substring(1);
        }
        if (result.isEmpty()) {
            return "received-file";
        }
        String stem = result.contains(".") ? result.substring(0, result.indexOf('.')) : result;
        if (WINDOWS_RESERVED.contains(stem.toUpperCase(Locale.ROOT))) {
            result = "_" + result;
        }
        return result.length() <= MAX_LENGTH ? result : result.substring(result.length() - MAX_LENGTH);
    }

    /** 在目录中找一个不会覆盖已有文件的位置：{@code a.txt} → {@code a (1).txt} → {@code a (2).txt}。 */
    public static File uniqueTarget(File directory, String safeName) {
        File candidate = new File(directory, safeName);
        if (!candidate.exists()) {
            return candidate;
        }
        int dot = safeName.lastIndexOf('.');
        String stem = dot > 0 ? safeName.substring(0, dot) : safeName;
        String extension = dot > 0 ? safeName.substring(dot) : "";
        for (int i = 1; ; i++) {
            candidate = new File(directory, stem + " (" + i + ")" + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
    }
}
