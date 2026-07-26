package com.aqishi.toolbox.vault;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Holds the operating-system lock that grants this process vault write access. */
public final class VaultFileLock implements AutoCloseable {
    private FileChannel channel;
    private FileLock lock;
    private boolean closed;

    public VaultFileLock(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path absolutePath = path.toAbsolutePath();
        Files.createDirectories(absolutePath.getParent());
        channel = FileChannel.open(
                path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException unavailable) {
            lock = null;
        } catch (IOException error) {
            closeChannelAfterFailure(error);
            throw error;
        } catch (RuntimeException error) {
            closeChannelAfterFailure(error);
            throw error;
        }
    }

    public synchronized boolean isWritable() {
        return !closed && lock != null && lock.isValid();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException ignored) {
                // Closing the channel below also releases its lock.
            } finally {
                lock = null;
            }
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // There is no caller recovery action once the lock is being released.
            } finally {
                channel = null;
            }
        }
    }

    private void closeChannelAfterFailure(Throwable original) {
        try {
            channel.close();
        } catch (IOException closeError) {
            original.addSuppressed(closeError);
        } finally {
            channel = null;
            closed = true;
        }
    }
}
