package com.example.temperate.service.user.profile.cache.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.service.user.profile.cache.UserProfileCacheValue;
import com.example.temperate.service.user.profile.cache.security.UserProfileCacheIdProtector;
import com.example.temperate.service.user.profile.config.UserProfileCacheProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 验证用户资料缓存使用单个 String、明文 JSON、加密 ID Key 及故障回源语义。
 */
@ExtendWith(MockitoExtension.class)
final class RedisUserProfileCacheStoreTest {

    private static final String KEY_BASE64 =
            Base64.getEncoder().encodeToString(new byte[32]);

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisUserProfileCacheStore store;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        store = new RedisUserProfileCacheStore(
                redisTemplate,
                new RedisKeyFactory("test"),
                new UserProfileCacheIdProtector(KEY_BASE64),
                new UserProfileCacheProperties(
                        KEY_BASE64,
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(15)),
                objectMapper,
                new SimpleMeterRegistry());
    }

    @Test
    void writesAndReadsPlainJsonUsingEncryptedUserIdKey() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        UserProfileCacheValue value = value();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        store.put(10001L, value);

        verify(valueOperations).set(
                keyCaptor.capture(),
                jsonCaptor.capture(),
                ttlCaptor.capture());
        assertThat(keyCaptor.getValue())
                .startsWith("ait:test:user:profile:v1:enc-id:")
                .doesNotContain("10001");
        assertThat(jsonCaptor.getValue())
                .contains("\"schemaVersion\":1")
                .contains("alice@example.test");
        assertThat(ttlCaptor.getValue()).isBetween(
                Duration.ofMinutes(5),
                Duration.ofMinutes(15));

        when(valueOperations.get(keyCaptor.getValue())).thenReturn(jsonCaptor.getValue());
        assertThat(store.find(10001L)).contains(value);
    }

    @Test
    void rejectsCorruptSnapshotAndUnlinksIt() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        String key = encryptedKey();
        when(valueOperations.get(key)).thenReturn("{\"schemaVersion\":99}");

        assertThat(store.find(10001L)).isEmpty();

        verify(redisTemplate).unlink(key);
    }

    @Test
    void treatsRedisReadAndWriteFailuresAsCacheMisses() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any())).thenThrow(new IllegalStateException("redis unavailable"));

        assertThat(store.find(10001L)).isEmpty();

        doThrow(new IllegalStateException("redis unavailable")).when(valueOperations).set(
                any(),
                any(),
                any(Duration.class));
        store.put(10001L, value());
    }

    @Test
    void evictsWithUnlinkAndLetsFailureReachAfterCommitRetry() {
        String key = encryptedKey();

        store.evict(10001L);

        verify(redisTemplate).unlink(eq(key));
    }

    @Test
    void evictsDistinctUsersWithOneMultiKeyUnlink() {
        ArgumentCaptor<Collection<String>> keys = ArgumentCaptor.forClass(
                Collection.class);

        store.evict(List.of(10001L, 10002L, 10001L));

        verify(redisTemplate).unlink(keys.capture());
        assertThat(keys.getValue())
                .hasSize(2)
                .allMatch(key -> key.startsWith(
                        "ait:test:user:profile:v1:enc-id:"))
                .noneMatch(key -> key.contains("10001")
                        || key.contains("10002"));
    }

    private String encryptedKey() {
        return new RedisKeyFactory("test").userProfileKey(
                new UserProfileCacheIdProtector(KEY_BASE64).protect(10001L));
    }

    private static UserProfileCacheValue value() {
        return new UserProfileCacheValue(
                UserProfileCacheValue.CURRENT_SCHEMA_VERSION,
                "Alice",
                "alice@example.test",
                "+14155550123",
                "https://cdn.example.test/avatar.webp",
                MembershipTier.FREE,
                5000L,
                null,
                OffsetDateTime.of(2026, 8, 6, 12, 0, 0, 0, ZoneOffset.UTC));
    }
}
