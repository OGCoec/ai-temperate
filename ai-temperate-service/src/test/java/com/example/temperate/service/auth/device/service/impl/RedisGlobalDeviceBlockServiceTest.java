package com.example.temperate.service.auth.device.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.auth.device.exception.GlobalDeviceBlockInfrastructureException;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 验证全局设备封禁查询服务使用统一设备 HMAC 口径生成 Redis Key，并把非法设备和 Redis 故障区分开。
 */
class RedisGlobalDeviceBlockServiceTest {

    private static final String DEVICE_ID = "550e8400-e29b-41d4-a716-446655440000";

    private StringRedisTemplate redisTemplate;
    private RedisKeyFactory keyFactory;
    private AuthSessionSecretProtector protector;
    private RedisGlobalDeviceBlockService service;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        keyFactory = new RedisKeyFactory("test");
        protector = new AuthSessionSecretProtector(new HmacSha256Identifier(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        service = new RedisGlobalDeviceBlockService(redisTemplate, keyFactory, protector);
    }

    @Test
    void readsStableGlobalDeviceBlockKeyForCanonicalUuid() {
        String expectedKey = keyFactory.globalDeviceBlockKey(protector.deviceBlock(DEVICE_ID));
        when(redisTemplate.getExpire(expectedKey, TimeUnit.SECONDS)).thenReturn(120L);

        assertThat(service.remainingBlockTtl(DEVICE_ID)).isEqualTo(Duration.ofSeconds(120));

        verify(redisTemplate).getExpire(expectedKey, TimeUnit.SECONDS);
    }

    @Test
    void readsTheSameBlockKeyFromAnAlreadyProtectedDigest() {
        var digest = protector.deviceBlock(DEVICE_ID);
        String expectedKey = keyFactory.globalDeviceBlockKey(digest);
        when(redisTemplate.getExpire(expectedKey, TimeUnit.SECONDS)).thenReturn(45L);

        assertThat(service.remainingBlockTtlByDigest(digest))
                .isEqualTo(Duration.ofSeconds(45));

        verify(redisTemplate).getExpire(expectedKey, TimeUnit.SECONDS);
    }

    @Test
    void rejectsMissingUppercaseAndWhitespaceDeviceIdsBeforeRedisLookup() {
        assertThatThrownBy(() -> service.remainingBlockTtl(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.remainingBlockTtl(
                        "550E8400-E29B-41D4-A716-446655440000"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.remainingBlockTtl(" " + DEVICE_ID))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void mapsRedisLookupFailureToInfrastructureException() {
        String expectedKey = keyFactory.globalDeviceBlockKey(protector.deviceBlock(DEVICE_ID));
        when(redisTemplate.getExpire(expectedKey, TimeUnit.SECONDS))
                .thenThrow(new IllegalStateException("down"));

        assertThatThrownBy(() -> service.remainingBlockTtl(DEVICE_ID))
                .isInstanceOf(GlobalDeviceBlockInfrastructureException.class);
    }
}
