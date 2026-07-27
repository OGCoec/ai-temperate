package com.example.temperate.service.auth.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.auth.login.limit.dto.ProtectedLoginAttempt;
import com.example.temperate.service.auth.login.limit.service.LoginRateLimitService;
import com.example.temperate.service.auth.login.limit.store.LoginFailureStore;
import com.example.temperate.service.auth.login.limit.store.impl.RedisLoginFailureStore;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 验证登录限流主体只使用既定设备维度的风控契约。
 */
class LoginLimitDeviceOnlyContractTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Test
    void protectedAttemptContainsOnlyTheIdentifiersUsedByDeviceRateLimiting() {
        assertThat(Arrays.stream(ProtectedLoginAttempt.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("identifierHash", "actorHash", "globalDeviceHash");
        assertThat(Arrays.stream(AuthSessionSecretProtector.class.getMethods())
                        .map(java.lang.reflect.Method::getName))
                .doesNotContain("loginNetwork");
    }

    @Test
    void successClearsBothDeviceFailureBucketsThroughTheStore() {
        assertThat(Arrays.stream(LoginFailureStore.class.getMethods())
                        .map(java.lang.reflect.Method::getName))
                .contains("clearFailures")
                .doesNotContain("clearSubjectFailures");
        assertThat(Arrays.stream(LoginRateLimitService.class.getMethods())
                        .map(java.lang.reflect.Method::getName))
                .contains("clearSubjectFailures")
                .doesNotContain("clearFailures");
    }

    @Test
    void redisStoreConstructorAndConfigurationDoNotExposeANetworkThreshold() throws Exception {
        assertThatCode(() -> RedisLoginFailureStore.class.getConstructor(
                                StringRedisTemplate.class,
                                RedisKeyFactory.class,
                                Duration.class,
                                int.class,
                                Duration.class))
                .doesNotThrowAnyException();

        String production = Files.readString(PROJECT_ROOT.resolve(
                "ai-temperate-web/src/main/resources/application.yml"));
        String test = Files.readString(PROJECT_ROOT.resolve(
                "ai-temperate-web/src/test/resources/application-test.yml"));
        assertThat(production).doesNotContain("network-max-failures:");
        assertThat(test).doesNotContain("network-max-failures:");
    }

    @Test
    void luaContractsUseOnlyDeviceKeysAndTheSelectedFailureBucket() throws Exception {
        Path lua = PROJECT_ROOT.resolve(
                "ai-temperate-service/src/main/resources/lua/auth-login");
        String check = Files.readString(lua.resolve("check_login_limit.lua"));
        String record = Files.readString(lua.resolve("record_login_failure.lua"));
        String clear = Files.readString(lua.resolve("clear_login_failures.lua"));

        assertThat(check).contains("KEYS[1]", "KEYS[2]")
                .doesNotContain("KEYS[3]", "networkBlocked");
        assertThat(record).contains("ARGV[3]", "KEYS[1]", "KEYS[2]", "KEYS[3]")
                .doesNotContain("ARGV[4]", "KEYS[4]", "networkMaximumFailures");
        assertThat(clear).contains("KEYS[1]", "KEYS[2]")
                .doesNotContain("KEYS[3]", "network");
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("ai-temperate-service"))
                    && Files.isDirectory(current.resolve("ai-temperate-web"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
