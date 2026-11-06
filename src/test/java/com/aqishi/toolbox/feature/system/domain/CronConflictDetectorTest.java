package com.aqishi.toolbox.feature.system.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.aqishi.toolbox.feature.system.domain.CronConflictDetector.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

class CronConflictDetectorTest {

    @Test
    void parsesTasksWithQuotesAndComments() {
        String input = "# Header comment\n"
                + "\"Nightly DB Backup\" 0 0 2 * * ? # full backup\n"
                + "log_archive 0 0 2 * * ?\n"
                + "0 */10 * * * ?\n";

        List<TaskSpec> tasks = CronConflictDetector.parseTasks(input);
        assertEquals(3, tasks.size());
        assertEquals("Nightly DB Backup", tasks.get(0).name());
        assertEquals("0 0 2 * * ?", tasks.get(0).expression());
        assertEquals("log_archive", tasks.get(1).name());
        assertEquals("task_1", tasks.get(2).name());
        assertEquals("0 */10 * * * ?", tasks.get(2).expression());
    }

    @Test
    void detectsCollisionsAtSameTimestamp() {
        String input = "task_a 0 0 2 * * ?\n"
                + "task_b 0 0 2 * * ?\n"
                + "task_c 0 30 3 * * ?\n";

        CronConflictDetector detector = new CronConflictDetector(Duration.ofHours(48), ZoneId.of("UTC"));
        String report = detector.analyze(input);

        assertTrue(report.contains("task_a, task_b") || report.contains("task_b, task_a"), report);
        assertTrue(report.contains("2 tasks:"), report);
    }

    @Test
    void reportsNoCollisionWhenTasksAreStaggered() {
        String input = "task_a 0 0 2 * * ?\n"
                + "task_b 0 30 2 * * ?\n";

        CronConflictDetector detector = new CronConflictDetector(Duration.ofHours(24), ZoneId.of("UTC"));
        String report = detector.analyze(input);

        assertTrue(report.contains("0 个时间碰撞点") || report.contains("0 collision point"), report);
    }

    @Test
    void rejectsEmptyInput() {
        assertThrows(IllegalArgumentException.class,
                () -> new CronConflictDetector(Duration.ofHours(24), ZoneId.of("UTC")).analyze("# only comments\n"));
    }
}
