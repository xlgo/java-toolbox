package com.aqishi.toolbox.feature.security.ui;

import com.aqishi.toolbox.feature.security.domain.BatchDigestService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JTable;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class BatchDigestPanelAsyncTest {

    @TempDir
    Path temp;

    @Test
    void processesFilesConcurrentlyAndKeepsTheTableInInputOrder() throws Exception {
        BlockingBatchDigestService service = new BlockingBatchDigestService(2);
        BatchDigestPanel panel = build(service);
        File first = Files.writeString(temp.resolve("first.txt"), "first").toFile();
        File second = Files.writeString(temp.resolve("second.txt"), "second").toFile();

        try {
            SwingUtilities.invokeAndWait(() -> {
                selectedFiles(panel).add(first);
                selectedFiles(panel).add(second);
                invoke(panel, "refreshTablePlaceholder");
                invoke(panel, "startBatchCalculation");
            });

            assertTrue(service.started.await(3, TimeUnit.SECONDS));
            assertTrue(service.maxRunning.get() >= 2, "files should hash concurrently");
            assertEnabled(panel, "addFilesBtn", false);
            assertEnabled(panel, "addDirBtn", false);
            assertEnabled(panel, "md5Check", false);

            service.release.countDown();
            awaitEdt(() -> batchWorker(panel) == null);
            assertEnabled(panel, "addFilesBtn", true);
            assertEnabled(panel, "addDirBtn", true);
            assertEnabled(panel, "md5Check", true);

            AtomicReference<String[]> names = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                JTable table = field(panel, "resultTable", JTable.class);
                names.set(new String[]{String.valueOf(table.getValueAt(0, 1)),
                        String.valueOf(table.getValueAt(1, 1))});
            });
            assertEquals(first.getName(), names.get()[0]);
            assertEquals(second.getName(), names.get()[1]);
        } finally {
            service.release.countDown();
            panel.closeResources();
        }
    }

    @Test
    void stoppingAJobCancelsItAndImmediatelyRestoresTheControls() throws Exception {
        BlockingBatchDigestService service = new BlockingBatchDigestService(1);
        BatchDigestPanel panel = build(service);
        File file = Files.writeString(temp.resolve("slow.txt"), "slow").toFile();

        try {
            SwingUtilities.invokeAndWait(() -> {
                selectedFiles(panel).add(file);
                invoke(panel, "refreshTablePlaceholder");
                invoke(panel, "startBatchCalculation");
            });
            assertTrue(service.started.await(3, TimeUnit.SECONDS));

            SwingUtilities.invokeAndWait(() -> field(panel, "stopBtn", JButton.class).doClick());
            assertEnabled(panel, "addFilesBtn", true);
            assertEnabled(panel, "md5Check", true);
            AtomicReference<String> status = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> status.set(field(panel, "progressLabel", JLabel.class).getText()));
            assertEquals("已中断", status.get());
        } finally {
            service.release.countDown();
            panel.closeResources();
        }
    }

    private static BatchDigestPanel build(BatchDigestService service) throws Exception {
        AtomicReference<BatchDigestPanel> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            BatchDigestPanel panel = new BatchDigestPanel(service);
            panel.getView();
            reference.set(panel);
        });
        return reference.get();
    }

    @SuppressWarnings("unchecked")
    private static List<File> selectedFiles(BatchDigestPanel panel) {
        return (List<File>) field(panel, "selectedFiles", List.class);
    }

    private static Object batchWorker(BatchDigestPanel panel) {
        AtomicReference<?> reference = field(panel, "batchWorker", AtomicReference.class);
        return reference.get();
    }

    private static void assertEnabled(BatchDigestPanel panel, String fieldName, boolean expected) throws Exception {
        AtomicReference<Boolean> enabled = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Object component = field(panel, fieldName, Object.class);
            if (component instanceof JButton button) {
                enabled.set(button.isEnabled());
            } else if (component instanceof JCheckBox checkBox) {
                enabled.set(checkBox.isEnabled());
            } else {
                throw new AssertionError("Unsupported component: " + fieldName);
            }
        });
        assertEquals(expected, enabled.get(), fieldName);
    }

    private static void awaitEdt(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            AtomicReference<Boolean> done = new AtomicReference<>(false);
            SwingUtilities.invokeAndWait(() -> done.set(condition.getAsBoolean()));
            if (done.get()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("Timed out waiting for the batch worker");
    }

    private static void invoke(BatchDigestPanel panel, String methodName) {
        try {
            Method method = BatchDigestPanel.class.getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(panel);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static <T> T field(BatchDigestPanel panel, String fieldName, Class<T> type) {
        try {
            Field field = BatchDigestPanel.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return type.cast(field.get(panel));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static final class BlockingBatchDigestService extends BatchDigestService {
        private final CountDownLatch started;
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger running = new AtomicInteger();
        private final AtomicInteger maxRunning = new AtomicInteger();

        private BlockingBatchDigestService(int fileCount) {
            this.started = new CountDownLatch(fileCount);
        }

        @Override
        public DigestResult computeFileDigest(File file, List<String> algorithms) {
            int nowRunning = running.incrementAndGet();
            maxRunning.accumulateAndGet(nowRunning, Math::max);
            started.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test worker was not released");
                }
                DigestResult result = new DigestResult(file);
                result.getHashes().put("SHA-256", file.getName());
                result.setSuccess(true);
                return result;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            } finally {
                running.decrementAndGet();
            }
        }
    }
}
