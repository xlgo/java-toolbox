package com.aqishi.toolbox.feature.monitor.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReceivedFileNamesTest {

    @TempDir
    Path temp;

    /** 回归：对端发来 ../ 路径时，文件会被写到下载目录之外。 */
    @Test
    void stripsDirectoryComponents() {
        assertEquals("evil.bat", ReceivedFileNames.sanitize("../../AppData/Roaming/evil.bat"));
        assertEquals("evil.bat", ReceivedFileNames.sanitize("..\\..\\Startup\\evil.bat"));
        assertEquals("passwd", ReceivedFileNames.sanitize("/etc/passwd"));
    }

    @Test
    void replacesIllegalCharactersAndReservedNames() {
        assertEquals("a_b_c.txt", ReceivedFileNames.sanitize("a<b>c.txt"));
        assertEquals("_CON.txt", ReceivedFileNames.sanitize("CON.txt"));
        assertEquals("report.pdf", ReceivedFileNames.sanitize("report.pdf. "));
        assertEquals("hidden", ReceivedFileNames.sanitize(".hidden"));
    }

    @Test
    void fallsBackWhenNothingUsableRemains() {
        assertEquals("received-file", ReceivedFileNames.sanitize(".."));
        assertEquals("received-file", ReceivedFileNames.sanitize("dir/"));
        assertEquals("received-file", ReceivedFileNames.sanitize(null));
    }

    /** 回归：同名文件会被先删除再覆盖。 */
    @Test
    void neverOverwritesExistingFiles() throws Exception {
        File dir = temp.toFile();
        Files.writeString(temp.resolve("a.txt"), "old");
        Files.writeString(temp.resolve("a (1).txt"), "old");

        assertEquals(new File(dir, "a (2).txt"), ReceivedFileNames.uniqueTarget(dir, "a.txt"));
        assertEquals(new File(dir, "b.txt"), ReceivedFileNames.uniqueTarget(dir, "b.txt"));
    }
}
