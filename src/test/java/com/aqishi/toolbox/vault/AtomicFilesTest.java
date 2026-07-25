package com.aqishi.toolbox.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicFilesTest {
    @TempDir
    Path temp;

    @Test
    void fallsBackToReplacingMoveWhenAtomicMoveIsUnsupported() throws Exception {
        Path source = temp.resolve("candidate");
        Path target = temp.resolve("config.properties");
        byte[] expected = new byte[]{1, 2, 3};
        Files.write(source, expected);
        AtomicBoolean fallbackUsed = new AtomicBoolean();

        AtomicFiles files = new AtomicFiles() {
            @Override
            protected void moveAtomically(Path moveSource, Path moveTarget) throws IOException {
                throw new AtomicMoveNotSupportedException(
                        moveSource.toString(), moveTarget.toString(), "simulated");
            }

            @Override
            protected void moveReplacing(Path moveSource, Path moveTarget) throws IOException {
                fallbackUsed.set(true);
                super.moveReplacing(moveSource, moveTarget);
            }
        };

        files.replace(source, target);

        assertTrue(fallbackUsed.get());
        assertArrayEquals(expected, Files.readAllBytes(target));
        assertFalse(Files.exists(source));
    }
}
