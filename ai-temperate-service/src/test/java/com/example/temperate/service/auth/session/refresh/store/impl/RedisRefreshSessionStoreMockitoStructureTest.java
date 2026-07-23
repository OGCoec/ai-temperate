package com.example.temperate.service.auth.session.refresh.store.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.auth.session.refresh.dto.command.NewRefreshSession;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 验证刷新会话 Redis 存储对模板调用、脚本参数和结构约束的单元契约。
 */
class RedisRefreshSessionStoreMockitoStructureTest {

    private static final HmacSha256Identifier HMAC = new HmacSha256Identifier(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void createUsesOnlyRtAndUserIndexKeysAndHashFieldExpiryScript() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        RedisKeyFactory keys = new RedisKeyFactory("test");
        PublicIdCodec publicIds = new PublicIdCodec();
        NewRefreshSession session = session(publicIds);
        AtomicReference<RedisScript<?>> scriptRef = new AtomicReference<>();
        AtomicReference<List<String>> keysRef = new AtomicReference<>();
        AtomicReference<Object[]> argumentsRef = new AtomicReference<>();
        doAnswer(invocation -> {
            scriptRef.set(invocation.getArgument(0));
            keysRef.set(invocation.getArgument(1));
            argumentsRef.set(invocation.getArgument(2));
            return List.of(
                    0L,
                    10001L,
                    session.publicId(),
                    session.csrfHash().value(),
                    session.email(),
                    session.phone(),
                    session.deviceHash().value(),
                    Instant.parse("2026-07-15T09:00:00Z").toEpochMilli());
        }).when(template).execute(
                any(RedisScript.class), anyList(), any(Object[].class));
        RedisRefreshSessionStore store = store(template, keys, publicIds);

        store.create(session);

        assertThat(keysRef.get()).containsExactly(
                keys.sessionRefreshTokenKey(session.refreshTokenHash()),
                keys.sessionUserIndexKey(session.userId()));
        assertThat(argumentsRef.get()).containsExactly(
                "10001",
                session.publicId(),
                session.refreshTokenHash().value(),
                session.deviceHash().value(),
                session.csrfHash().value(),
                session.email(),
                session.phone(),
                "10",
                Long.toString(Duration.ofHours(3).toMillis()));
        assertThat(scriptRef.get().getScriptAsString())
                .contains("HPEXPIREAT", "PEXPIREAT", "'userId'", "'deviceHash'")
                .doesNotContain("familyHash", "sessionId", "passwordVersion");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void mapsTemplateExecutionFailuresToControlledStoreFailure() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        IllegalStateException driverFailure = new IllegalStateException("driver detail");
        doThrow(driverFailure).when(template).execute(
                any(RedisScript.class), anyList(), any(Object[].class));
        RedisRefreshSessionStore store = store(
                template, new RedisKeyFactory("test"), new PublicIdCodec());

        assertThatThrownBy(() -> store.validateAndRenew(
                id("refresh"), id("device"), id("csrf")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Redis refresh session script execution failed.")
                .hasCause(driverFailure);
    }

    @Test
    void revokeAllUsesPipelineBulkUnlinkWithoutTheRemovedLuaLoop() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/service/auth/session/refresh/store/impl/"
                        + "RedisRefreshSessionStore.java"));

        assertThat(source).contains("executePipelined", "unlink");
        assertThat(source).doesNotContain("REVOKE_ALL_SCRIPT", "for (");
    }

    @Test
    void mapsSessionIndexPipelineFailureToControlledStoreFailure() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        RuntimeException driverFailure = new RuntimeException("driver detail");
        doThrow(driverFailure).when(template).executePipelined(any(RedisCallback.class));
        RedisRefreshSessionStore store = store(
                template, new RedisKeyFactory("test"), new PublicIdCodec());

        assertThatThrownBy(() -> store.revokeAllForUser(10001L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Redis refresh session index pipeline failed.")
                .hasCause(driverFailure);
    }

    private static RedisRefreshSessionStore store(
            StringRedisTemplate template,
            RedisKeyFactory keys,
            PublicIdCodec publicIds) {
        return new RedisRefreshSessionStore(
                template, keys, publicIds, Duration.ofHours(3), 10, 100);
    }

    private static NewRefreshSession session(PublicIdCodec publicIds) {
        return new NewRefreshSession(
                10001L,
                publicIds.encode(10001L),
                id("refresh"),
                id("device"),
                id("csrf"),
                "person@example.test",
                "+8613812345678");
    }

    private static HmacIdentifier id(String value) {
        return HMAC.identify(value);
    }
}
