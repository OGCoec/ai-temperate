package com.example.temperate.service.redis.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 该契约测试是来锁定 Redisson Core 单例配置、三十秒看门狗和现有 Spring Data Redis 连接参数复用方式。
 */
final class RedissonClientConfigurationContractTest {

    @Test
    void usesCoreClientWithThirtySecondWatchdogAndNoStarterReplacement() throws IOException {
        Path root = findProjectRoot();
        String source = Files.readString(root.resolve(
                "ai-temperate-service/src/main/java/com/example/temperate/service/redis/config/RedissonClientConfiguration.java"));
        String parentPom = Files.readString(root.resolve("pom.xml"));
        String servicePom = Files.readString(root.resolve("ai-temperate-service/pom.xml"));

        assertThat(source)
                .contains("LOCK_WATCHDOG_TIMEOUT_MILLIS = 30_000L")
                .contains("@EnableConfigurationProperties(RedisProperties.class)")
                .contains("setLazyInitialization(true)")
                .contains("setLockWatchdogTimeout(LOCK_WATCHDOG_TIMEOUT_MILLIS)")
                .contains("RedisProperties properties")
                .contains("destroyMethod = \"shutdown\"");
        assertThat(parentPom)
                .contains("<redisson.version>4.6.1</redisson.version>")
                .contains("<artifactId>redisson</artifactId>");
        assertThat(servicePom)
                .contains("<artifactId>redisson</artifactId>")
                .doesNotContain("redisson-spring-boot-starter");
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("ai-temperate-service"))
                    && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
