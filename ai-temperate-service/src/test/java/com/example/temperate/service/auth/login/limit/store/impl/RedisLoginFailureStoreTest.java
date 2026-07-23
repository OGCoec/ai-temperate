package com.example.temperate.service.auth.login.limit.store.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.auth.login.limit.dto.ProtectedLoginAttempt;
import com.example.temperate.service.auth.login.limit.enums.LoginFailureBucket;
import com.example.temperate.service.auth.login.limit.enums.LoginLimitDecision;
import com.example.temperate.service.auth.login.limit.exception.LoginRateLimitInfrastructureException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 验证 Redis 登录失败存储对脚本结果和异常状态的映射。
 */
class RedisLoginFailureStoreTest {

    private RedisHarness redis;
    private RedisKeyFactory keyFactory;
    private RedisLoginFailureStore store;
    private ProtectedLoginAttempt attempt;

    @BeforeEach
    void setUp() {
        redis = new RedisHarness();
        keyFactory = new RedisKeyFactory("test");
        StringRedisTemplate template = mock(StringRedisTemplate.class, redis);
        store = new RedisLoginFailureStore(
                template, keyFactory, Duration.ofMinutes(5), 5, Duration.ofMinutes(15));
        HmacSha256Identifier hmac = new HmacSha256Identifier(
                "login-limit-test-secret-0123456789".getBytes(StandardCharsets.UTF_8));
        attempt = new ProtectedLoginAttempt(
                hmac.identify("subject"),
                hmac.identify("actor"),
                hmac.identify("network"),
                hmac.identify("global-device"));
    }

    @Test
    void checkRecordAndClearUseExactTypedKeysAndBoundedArguments() {
        redis.enqueue(0L, 1L, 0L, 1L, 1L);

        assertThat(store.check(attempt, LoginFailureBucket.PASSWORD))
                .isEqualTo(LoginLimitDecision.ALLOWED);
        assertThat(store.check(attempt, LoginFailureBucket.PASSWORD))
                .isEqualTo(LoginLimitDecision.BLOCKED);
        assertThat(store.recordFailure(attempt, LoginFailureBucket.PASSWORD))
                .isEqualTo(LoginLimitDecision.ALLOWED);
        assertThat(store.recordFailure(attempt, LoginFailureBucket.CODE))
                .isEqualTo(LoginLimitDecision.BLOCKED);
        store.clearFailures(attempt);

        ScriptCall check = redis.calls().get(0);
        assertThat(check.keys()).containsExactly(
                keyFactory.loginBlockKey(attempt.actorHash()),
                keyFactory.globalDeviceBlockKey(attempt.globalDeviceHash()));
        assertThat(check.arguments()).isEmpty();

        ScriptCall record = redis.calls().get(2);
        assertThat(record.keys()).containsExactly(
                keyFactory.loginPasswordFailureKey(attempt.actorHash()),
                keyFactory.loginBlockKey(attempt.actorHash()),
                keyFactory.globalDeviceBlockKey(attempt.globalDeviceHash()));
        assertThat(record.arguments()).containsExactly("300000", "5", "900000");

        ScriptCall codeRecord = redis.calls().get(3);
        assertThat(codeRecord.keys()).containsExactly(
                keyFactory.loginCodeFailureKey(attempt.actorHash()),
                keyFactory.loginBlockKey(attempt.actorHash()),
                keyFactory.globalDeviceBlockKey(attempt.globalDeviceHash()));

        ScriptCall clear = redis.calls().get(4);
        assertThat(clear.keys()).containsExactly(
                keyFactory.loginPasswordFailureKey(attempt.actorHash()),
                keyFactory.loginCodeFailureKey(attempt.actorHash()));
        assertThat(clear.arguments()).isEmpty();
        assertThat(redis.calls().toString())
                .doesNotContain("person@example.test", "203.0.113.10", "install-id");
    }

    @Test
    void redisFailuresAndMissingResultsFailClosed() {
        redis.enqueue(new IllegalStateException("redis offline"), RedisHarness.NULL_RESULT);

        assertThatThrownBy(() -> store.check(attempt, LoginFailureBucket.PASSWORD))
                .isInstanceOf(LoginRateLimitInfrastructureException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.recordFailure(attempt, LoginFailureBucket.PASSWORD))
                .isInstanceOf(LoginRateLimitInfrastructureException.class);
    }

    @Test
    void rejectsUnboundedOrNonPositiveConfiguration() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);

        assertThatThrownBy(() -> new RedisLoginFailureStore(
                        template, keyFactory, Duration.ZERO, 5, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisLoginFailureStore(
                        template, keyFactory, Duration.ofMinutes(5), 0,
                        Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisLoginFailureStore(
                        template, keyFactory, Duration.ofHours(1).plusMillis(1), 5,
                        Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisLoginFailureStore(
                        template, keyFactory, Duration.ofMinutes(5), 21,
                        Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisLoginFailureStore(
                        template, keyFactory, Duration.ofNanos(1), 5,
                        Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisLoginFailureStore(
                        template, keyFactory, Duration.ofMinutes(5), 5, Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisLoginFailureStore(
                        template, keyFactory, Duration.ofMinutes(5), 5, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisLoginFailureStore(
                        template, keyFactory, Duration.ofMinutes(5), 5, Duration.ofDays(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new RedisLoginFailureStore(
                        template, keyFactory, Duration.ofHours(1), 20, Duration.ofDays(1)))
                .doesNotThrowAnyException();
    }

    @Test
    void luaContractsAreAtomicBoundedAndNeverScanKeys() throws Exception {
        Path lua = Path.of("src/main/resources/lua/auth-login");
        String check = normalized(Files.readString(lua.resolve("check_login_limit.lua")));
        String record = normalized(Files.readString(lua.resolve("record_login_failure.lua")));
        String clear = normalized(Files.readString(lua.resolve("clear_login_failures.lua")));

        assertThat(check)
                .contains("redis.call('exists', keys[1])")
                .contains("redis.call('exists', keys[2])")
                .doesNotContain("keys[3]", "network");
        assertThat(record).contains(
                "local windowmillis = tonumber(argv[1])",
                "local maximumfailures = tonumber(argv[2])",
                "local blockmillis = tonumber(argv[3])",
                "local failures = redis.call('incr', keys[1])",
                "if failures > maximumfailures then",
                "redis.call('psetex', keys[2], blockmillis, '1')",
                "redis.call('psetex', keys[3], blockmillis, '1')");
        assertThat(record.indexOf("tonumber(argv[1])"))
                .isLessThan(record.indexOf("tonumber(argv[2])"));
        assertThat(record.indexOf("tonumber(argv[2])"))
                .isLessThan(record.indexOf("tonumber(argv[3])"));
        assertThat(record).doesNotContain("keys[4]", "network");
        assertThat(clear).contains("return redis.call('unlink', keys[1], keys[2])")
                .doesNotContain("keys[3]", "network");
        assertThat(check + record + clear).doesNotContain(
                "redis.call('keys'", "email", "phone", "deviceinstallationid", "clientip");
    }

    private static String normalized(String script) {
        return script.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private record ScriptCall(List<String> keys, List<Object> arguments) {
    }

    private static final class RedisHarness implements Answer<Object> {

        private static final Object NULL_RESULT = new Object();

        private final Deque<Object> results = new ArrayDeque<>();
        private final List<ScriptCall> calls = new ArrayList<>();

        void enqueue(Object... values) {
            results.addAll(Arrays.asList(values));
        }

        List<ScriptCall> calls() {
            return List.copyOf(calls);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object answer(InvocationOnMock invocation) throws Throwable {
            Object[] raw = invocation.getRawArguments();
            if ("execute".equals(invocation.getMethod().getName())
                    && raw.length >= 3
                    && raw[0] instanceof RedisScript<?>) {
                Object[] arguments = raw[2] instanceof Object[] values
                        ? values.clone()
                        : Arrays.copyOfRange(raw, 2, raw.length);
                calls.add(new ScriptCall(
                        List.copyOf((List<String>) raw[1]),
                        List.copyOf(Arrays.asList(arguments))));
                Object result = results.removeFirst();
                if (result instanceof RuntimeException exception) {
                    throw exception;
                }
                return result == NULL_RESULT ? null : result;
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        }
    }
}
