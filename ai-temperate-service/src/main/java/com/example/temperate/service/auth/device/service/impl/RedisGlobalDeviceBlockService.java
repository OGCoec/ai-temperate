package com.example.temperate.service.auth.device.service.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.device.exception.GlobalDeviceBlockInfrastructureException;
import com.example.temperate.service.auth.device.service.GlobalDeviceBlockService;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 使用 Redis 全局设备封禁 Key 查询认证入口是否应该被临时拒绝。
 *
 * <p>安全边界：设备安装标识先经过统一 HMAC 域转换，再拼入 Redis Key；返回值只暴露剩余封禁时长，不泄露内部 Key 或设备摘要。</p>
 */
@Service
public final class RedisGlobalDeviceBlockService implements GlobalDeviceBlockService {

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final AuthSessionSecretProtector protector;

    public RedisGlobalDeviceBlockService(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            AuthSessionSecretProtector protector) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.protector = Objects.requireNonNull(protector);
    }

    @Override
    public Duration remainingBlockTtl(String deviceInstallationId) {
        HmacIdentifier globalDeviceHash = protector.deviceBlock(deviceInstallationId);
        return remainingBlockTtlByDigest(globalDeviceHash);
    }

    @Override
    public Duration remainingBlockTtlByDigest(HmacIdentifier globalDeviceBlockDigest) {
        HmacIdentifier validDigest = Objects.requireNonNull(globalDeviceBlockDigest);
        String key = keyFactory.globalDeviceBlockKey(validDigest);
        try {
            // getExpire 返回 Long 秒数，-2 表示 Key 不存在，-1 表示无过期时间，正数为剩余秒数。
            Long seconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            if (seconds == null || seconds <= 0) {
                return Duration.ZERO;
            }
            return Duration.ofSeconds(seconds);
        } catch (RuntimeException exception) {
            throw new GlobalDeviceBlockInfrastructureException(
                    "Global device block lookup failed.", exception);
        }
    }
}
