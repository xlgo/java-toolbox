package com.aqishi.toolbox.feature.system.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessKillPolicyTest {

    private static final long SELF = 31337;

    @Test
    void refusesWindowsSystemProcesses() {
        for (long pid = 0; pid <= 4; pid++) {
            assertEquals(Optional.of(ProcessKillPolicy.Refusal.SYSTEM_PROCESS),
                    ProcessKillPolicy.check(pid, OsFamily.WINDOWS, SELF), "pid " + pid);
        }
        assertTrue(ProcessKillPolicy.check(5, OsFamily.WINDOWS, SELF).isEmpty());
        // Unix 的 init 在 Windows 上只是普通进程号。
        assertTrue(ProcessKillPolicy.check(1234, OsFamily.WINDOWS, SELF).isEmpty());
    }

    @Test
    void refusesInitAndProcessGroupSignalsOnUnix() {
        for (OsFamily os : new OsFamily[]{OsFamily.LINUX, OsFamily.MAC, OsFamily.OTHER_UNIX}) {
            assertEquals(Optional.of(ProcessKillPolicy.Refusal.SYSTEM_PROCESS), ProcessKillPolicy.check(1, os, SELF));
            // kill 0 / kill -1 会波及整个进程组乃至所有进程。
            assertEquals(Optional.of(ProcessKillPolicy.Refusal.INVALID_PID), ProcessKillPolicy.check(0, os, SELF));
            assertEquals(Optional.of(ProcessKillPolicy.Refusal.INVALID_PID), ProcessKillPolicy.check(-1, os, SELF));
            assertTrue(ProcessKillPolicy.check(2, os, SELF).isEmpty());
            assertTrue(ProcessKillPolicy.check(4, os, SELF).isEmpty());
        }
    }

    @Test
    void refusesOwnProcess() {
        assertEquals(Optional.of(ProcessKillPolicy.Refusal.SELF),
                ProcessKillPolicy.check(SELF, OsFamily.WINDOWS, SELF));
        assertEquals(Optional.of(ProcessKillPolicy.Refusal.SELF),
                ProcessKillPolicy.check(SELF, OsFamily.LINUX, SELF));
        assertEquals(Optional.of(ProcessKillPolicy.Refusal.INVALID_PID),
                ProcessKillPolicy.check(-7, OsFamily.WINDOWS, SELF));
    }

    @Test
    void detectsOsFamilies() {
        assertEquals(OsFamily.WINDOWS, OsFamily.detect("Windows 11"));
        assertEquals(OsFamily.MAC, OsFamily.detect("Mac OS X"));
        assertEquals(OsFamily.LINUX, OsFamily.detect("Linux"));
        assertEquals(OsFamily.OTHER_UNIX, OsFamily.detect("FreeBSD"));
        assertEquals(OsFamily.OTHER_UNIX, OsFamily.detect(null));
    }
}
