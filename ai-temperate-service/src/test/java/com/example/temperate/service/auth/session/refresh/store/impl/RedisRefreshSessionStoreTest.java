package com.example.temperate.service.auth.session.refresh.store.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.auth.session.refresh.dto.command.NewRefreshSession;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionRevocation;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionSnapshot;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionValidation;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 验证刷新会话 Redis 存储对脚本状态码和异常的领域结果映射。
 */
class RedisRefreshSessionStoreTest {

    private static final long USER_ID = 10001L;
    private static final Instant EXPIRES_AT = Instant.parse("2026-07-15T09:00:00Z");
    private static final HmacSha256Identifier HMAC = new HmacSha256Identifier(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private StringRedisTemplate template;
    private RedisKeyFactory keyFactory;
    private PublicIdCodec publicIds;
    private RedisRefreshSessionStore store;
    private NewRefreshSession session;

    @BeforeEach
    void setUp() {
        template = mock(StringRedisTemplate.class);
        keyFactory = new RedisKeyFactory("test");
        publicIds = new PublicIdCodec();
        store = new RedisRefreshSessionStore(
                template,
                keyFactory,
                publicIds,
                Duration.ofHours(3),
                10,
                100);
        session = new NewRefreshSession(
                USER_ID,
                publicIds.encode(USER_ID),
                id("refresh"),
                id("device"),
                id("csrf"),
                "person@example.test",
                "+8613812345678");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void createsSixFieldFixedRefreshSessionSnapshot() {
        doReturn(snapshot(0L, session.csrfHash())).when(template).execute(
                any(RedisScript.class), anyList(), any(Object[].class));

        RefreshSessionSnapshot created = store.create(session);

        assertThat(created.userId()).isEqualTo(USER_ID);
        assertThat(created.publicId()).isEqualTo(publicIds.encode(USER_ID));
        assertThat(created.csrfHash()).isEqualTo(session.csrfHash().value());
        assertThat(created.email()).isEqualTo("person@example.test");
        assertThat(created.phone()).isEqualTo("+8613812345678");
        assertThat(created.deviceHash()).isEqualTo(session.deviceHash().value());
        assertThat(created.expiresAt()).isEqualTo(EXPIRES_AT);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void mapsValidationAndBootstrapStatusesWithoutRotatingRefreshHash() {
        HmacIdentifier newCsrf = id("new-csrf");
        doReturn(snapshot(0L, session.csrfHash()), List.of(1L), List.of(2L),
                List.of(3L), List.of(4L), snapshot(0L, newCsrf))
                .when(template).execute(
                        any(RedisScript.class), anyList(), any(Object[].class));

        assertThat(store.validateAndRenew(
                session.refreshTokenHash(), session.deviceHash(), session.csrfHash()).status())
                .isEqualTo(RefreshSessionValidation.Status.VALID);
        assertThat(store.validateAndRenew(
                session.refreshTokenHash(), session.deviceHash(), session.csrfHash()).status())
                .isEqualTo(RefreshSessionValidation.Status.MISSING_OR_EXPIRED);
        assertThat(store.validateAndRenew(
                session.refreshTokenHash(), session.deviceHash(), session.csrfHash()).status())
                .isEqualTo(RefreshSessionValidation.Status.DEVICE_MISMATCH);
        assertThat(store.validateAndRenew(
                session.refreshTokenHash(), session.deviceHash(), session.csrfHash()).status())
                .isEqualTo(RefreshSessionValidation.Status.CSRF_MISMATCH);
        assertThat(store.validateAndRenew(
                session.refreshTokenHash(), session.deviceHash(), session.csrfHash()).status())
                .isEqualTo(RefreshSessionValidation.Status.INDEX_MISSING);

        RefreshSessionValidation bootstrap = store.bootstrapAndRenew(
                session.refreshTokenHash(), session.deviceHash(), newCsrf);
        assertThat(bootstrap.status()).isEqualTo(RefreshSessionValidation.Status.VALID);
        assertThat(bootstrap.session().csrfHash()).isEqualTo(newCsrf.value());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void mapsCurrentAndAllSessionRevocationResults() {
        doReturn(1L, 0L, -2L, -3L, -4L).when(template).execute(
                any(RedisScript.class), anyList(), any(Object[].class));

        assertThat(revoke().status()).isEqualTo(RefreshSessionRevocation.Status.REVOKED);
        assertThat(revoke().status())
                .isEqualTo(RefreshSessionRevocation.Status.MISSING_OR_EXPIRED);
        assertThat(revoke().status()).isEqualTo(RefreshSessionRevocation.Status.DEVICE_MISMATCH);
        assertThat(revoke().status()).isEqualTo(RefreshSessionRevocation.Status.CSRF_MISMATCH);
        assertThat(revoke().status())
                .isEqualTo(RefreshSessionRevocation.Status.INDEX_BOUND_EXCEEDED);

        doReturn(
                List.of(
                        2L,
                        List.of(
                                keyFactory.sessionRefreshTokenKey(session.refreshTokenHash()),
                                keyFactory.sessionRefreshTokenKey(id("refresh-other"))),
                        0L,
                        List.of()),
                List.of(3L))
                .when(template)
                .executePipelined(any(RedisCallback.class));

        assertThat(store.revokeAllForUser(USER_ID)).isEqualTo(2);
    }

    @Test
    void rejectsUnsupportedTtlAndUnsafeBounds() {
        RedisKeyFactory keys = new RedisKeyFactory("test");
        assertThatThrownBy(() -> new RedisRefreshSessionStore(
                template, keys, publicIds, Duration.ofMinutes(179), 10, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisRefreshSessionStore(
                template, keys, publicIds, Duration.ofHours(3), 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisRefreshSessionStore(
                template, keys, publicIds, Duration.ofHours(3), 10, 9))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RefreshSessionRevocation revoke() {
        return store.revoke(
                session.refreshTokenHash(), session.deviceHash(), session.csrfHash());
    }

    private List<Object> snapshot(long status, HmacIdentifier csrfHash) {
        return List.of(
                status,
                USER_ID,
                publicIds.encode(USER_ID),
                csrfHash.value(),
                "person@example.test",
                "+8613812345678",
                session.deviceHash().value(),
                EXPIRES_AT.toEpochMilli());
    }

    private static HmacIdentifier id(String value) {
        return HMAC.identify(value);
    }
}
