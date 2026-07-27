package com.example.temperate.service.admin.config.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.config.AdminConfiguration;
import com.example.temperate.service.admin.config.AdminConfigurationState;
import com.example.temperate.service.admin.config.AdminStatus;
import com.example.temperate.service.admin.config.properties.AdminProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证隐藏管理员配置的状态判定、一次性初始化和损坏后 Fail Closed 行为。
 */
class FileAdminConfigurationServiceImplTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-23T13:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;

    @Test
    void reportsUninitializedOnlyWhenConfigurationAndMarkerAreBothAbsent() {
        FileAdminConfigurationServiceImpl service = service();

        assertThat(service.inspect(true).state())
                .isEqualTo(AdminConfigurationState.UNINITIALIZED);
    }

    @Test
    void initializesOnceAndLoadsActiveConfiguration() {
        FileAdminConfigurationServiceImpl service = service();
        AdminConfiguration configuration = configuration();

        service.initialize(configuration);

        assertThat(service.inspect(true).state()).isEqualTo(AdminConfigurationState.ACTIVE);
        assertThat(service.requireActive().email()).isEqualTo("admin@example.test");
        assertThatThrownBy(() -> service.initialize(configuration))
                .hasMessageContaining("initialized");
    }

    @Test
    void concurrentInitializationAllowsExactlyOneWinner() throws Exception {
        FileAdminConfigurationServiceImpl service = service();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var task = (java.util.concurrent.Callable<AdminErrorCode>) () -> {
                ready.countDown();
                start.await();
                try {
                    service.initialize(configuration());
                    return null;
                } catch (AdminException exception) {
                    return exception.code();
                }
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            ready.await();
            start.countDown();

            var outcomes = Arrays.asList(first.get(), second.get());
            assertThat(outcomes).containsExactlyInAnyOrder(
                    null, AdminErrorCode.ADMIN_ALREADY_INITIALIZED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void markerWithoutValidConfigurationIsCorruptAndNeverReopensRegistration()
            throws Exception {
        Files.createDirectories(directory.resolve(".admin"));
        Files.writeString(directory.resolve(".admin/.initialized"), "1");
        FileAdminConfigurationServiceImpl service = service();

        assertThat(service.inspect(true).state()).isEqualTo(AdminConfigurationState.CORRUPT);
    }

    @Test
    void nonRegularConfigurationArtifactDoesNotReopenInitialization() throws Exception {
        Files.createDirectories(directory.resolve(".admin/complete.yaml"));
        FileAdminConfigurationServiceImpl service = service();

        assertThat(service.inspect(true).state()).isEqualTo(AdminConfigurationState.CORRUPT);
    }

    private FileAdminConfigurationServiceImpl service() {
        AdminProperties properties = AdminProperties.testDefaults(
                directory.resolve(".admin/complete.yaml"));
        return new FileAdminConfigurationServiceImpl(properties, CLOCK);
    }

    private static AdminConfiguration configuration() {
        return new AdminConfiguration(
                1,
                AdminStatus.ACTIVE,
                "admin@example.test",
                "US",
                "+12164202316",
                "{bcrypt}$2a$10$" + "A".repeat(53),
                CLOCK.instant(),
                CLOCK.instant());
    }
}
