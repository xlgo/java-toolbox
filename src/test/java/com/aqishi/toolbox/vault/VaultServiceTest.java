package com.aqishi.toolbox.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultServiceTest {
    @TempDir
    Path temp;

    @Test
    void unlockNotifiesBothListenersAndReturnsDefensiveSlices() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            support.repository().create(support.sampleData(), "master".toCharArray()).close();
            TestClock clock = new TestClock();
            TestScheduler scheduler = new TestScheduler();
            VaultService service = service(support, clock, scheduler);
            List<VaultState> first = new ArrayList<>();
            List<VaultState> second = new ArrayList<>();
            service.addListener(first::add);
            service.addListener(second::add);

            service.unlock("master".toCharArray()).get();

            assertEquals(VaultState.UNLOCKED, service.getState());
            assertEquals(first, second);
            assertEquals(1, service.getPasswordAccounts().size());
            assertEquals(1, service.getTotpAccounts().size());
            assertThrows(UnsupportedOperationException.class,
                    () -> service.getPasswordAccounts().add(new PasswordAccount()));
            service.close();
        }
    }

    @Test
    void locksAfterConfiguredIdleTimeAndTouchResetsIt() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            support.repository().create(support.sampleData(), "master".toCharArray()).close();
            TestClock clock = new TestClock();
            TestScheduler scheduler = new TestScheduler();
            VaultService service = service(support, clock, scheduler);
            service.unlock("master".toCharArray()).get();

            clock.advanceMinutes(4);
            scheduler.tick();
            assertEquals(VaultState.UNLOCKED, service.getState());
            service.touch();
            clock.advanceMinutes(4);
            scheduler.tick();
            assertEquals(VaultState.UNLOCKED, service.getState());
            clock.advanceMinutes(1);
            scheduler.tick();

            assertEquals(VaultState.LOCKED, service.getState());
            assertTrue(service.getPasswordAccounts().isEmpty());
            service.close();
        }
    }

    @Test
    void savesBothDataSlicesAndWipesOwnedPasswords() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            VaultService service = service(support, new TestClock(), new TestScheduler());
            char[] password = "master".toCharArray();
            service.create(password).get();
            assertArrayEquals(new char[password.length], password);

            service.replacePasswordAccounts(Collections.singletonList(
                    new PasswordAccount("Mail", "me", "secret", "url"))).get();
            service.replaceTotpAccounts(Collections.singletonList(
                    new TotpAccount("1", "OTP", "JBSWY3DPEHPK3PXP", "Issuer",
                            "SHA1", 6, 30, true))).get();
            service.lock();

            service.unlock("master".toCharArray()).get();
            assertEquals("secret", service.getPasswordAccounts().get(0).getPassword());
            assertEquals("JBSWY3DPEHPK3PXP", service.getTotpAccounts().get(0).getSecret());
            service.close();
        }
    }

    @Test
    void changesPasswordTransactionally() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            VaultService service = service(support, new TestClock(), new TestScheduler());
            service.create("old".toCharArray()).get();

            char[] current = "old".toCharArray();
            char[] replacement = "new".toCharArray();
            service.changePassword(current, replacement).get();
            assertArrayEquals(new char[current.length], current);
            assertArrayEquals(new char[replacement.length], replacement);
            service.lock();

            assertThrows(Exception.class, () -> service.unlock("old".toCharArray()).get());
            service.unlock("new".toCharArray()).get();
            assertEquals(VaultState.UNLOCKED, service.getState());
            service.close();
        }
    }

    @Test
    void validatesTimeoutAndEmptyMasterPassword() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            VaultService service = service(support, new TestClock(), new TestScheduler());
            assertThrows(IllegalArgumentException.class, () -> service.setIdleMinutes(0));
            assertThrows(IllegalArgumentException.class, () -> service.setIdleMinutes(2));
            service.setIdleMinutes(1);
            service.setIdleMinutes(5);
            service.setIdleMinutes(10);
            service.setIdleMinutes(30);
            assertThrows(Exception.class, () -> service.create(new char[0]).get());
            assertFalse(service.isInitialized());
            service.close();
        }
    }

    @Test
    void existingVaultWithLegacySourcesRequiresMigrationCleanup() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            support.repository().create(new VaultData(), "master".toCharArray()).close();
            java.util.Properties properties = new java.util.Properties();
            properties.setProperty("totp.accounts", "[]");
            properties.setProperty("theme", "Arc");
            java.nio.file.Files.createDirectories(
                    support.paths().getLegacyConfigFile().getParent());
            try (java.io.OutputStream output = java.nio.file.Files.newOutputStream(
                    support.paths().getLegacyConfigFile())) {
                properties.store(output, "legacy");
            }
            // An empty [] does not represent a sensitive source and is not cleanup-required.
            java.nio.file.Files.delete(support.paths().getLegacyConfigFile());
            properties.setProperty("totp.accounts", "[{\"id\":\"1\",\"label\":\"Mail\","
                    + "\"secret\":\"JBSWY3DPEHPK3PXP\"}]");
            try (java.io.OutputStream output = java.nio.file.Files.newOutputStream(
                    support.paths().getLegacyConfigFile())) {
                properties.store(output, "legacy");
            }
            VaultService service = service(support, new TestClock(), new TestScheduler());
            assertEquals(VaultState.MIGRATION_REQUIRED, service.getState());
            service.close();
        }
    }

    private static VaultService service(VaultTestSupport support,
                                        TestClock clock,
                                        TestScheduler scheduler) throws Exception {
        return new VaultService(
                support.repository(),
                new LegacyVaultMigrator(support.paths(), support.repository(),
                        new AtomicFiles(), new VaultCrypto()),
                Runnable::run, Runnable::run, clock, scheduler, 5);
    }

    private static final class TestClock implements VaultClock {
        private long now;

        @Override
        public long currentTimeMillis() {
            return now;
        }

        void advanceMinutes(int minutes) {
            now += TimeUnit.MINUTES.toMillis(minutes);
        }
    }

    private static final class TestScheduler implements VaultScheduler {
        private Runnable periodic;

        @Override
        public Cancellable schedule(Runnable task, long delay, TimeUnit unit) {
            return () -> { };
        }

        @Override
        public Cancellable scheduleAtFixedRate(
                Runnable task, long initialDelay, long period, TimeUnit unit) {
            periodic = task;
            return () -> periodic = null;
        }

        void tick() {
            if (periodic != null) periodic.run();
        }

        @Override
        public void close() {
            periodic = null;
        }
    }
}
