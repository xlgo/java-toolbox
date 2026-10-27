package com.aqishi.toolbox.infra.network.scan;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

class ScanTargetFilesTest {
    @TempDir Path temp;

    @Test
    void utf8BomCommentsAndMultipleTargetsImportWithoutNetworkAccess() throws Exception {
        Path file = temp.resolve("targets.txt");
        Files.writeString(
                file, "\uFEFF# servers\n127.0.0.1 # local\n::1/128\n// comment\n10.20.30.*\n");
        assertEquals("127.0.0.1,::1/128,10.20.30.*", ScanTargetFiles.read(file));
    }

    @Test
    void oversizedAndNonUtf8FilesAreRejectedBeforeReplacingTargets() throws Exception {
        Path file = temp.resolve("large.txt");
        Files.write(file, new byte[1024 * 1024 + 1]);
        assertThrows(java.io.IOException.class, () -> ScanTargetFiles.read(file));
        Files.write(file, new byte[] {(byte) 0xff});
        assertEquals(
                "portscan.error.fileEncoding",
                assertThrows(java.io.IOException.class, () -> ScanTargetFiles.read(file))
                        .getMessage());
    }
}
