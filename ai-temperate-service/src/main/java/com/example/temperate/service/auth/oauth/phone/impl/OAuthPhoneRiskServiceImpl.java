package com.example.temperate.service.auth.oauth.phone.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.auth.oauth.flow.ProtectedOAuthFlowAccess;
import com.example.temperate.service.auth.oauth.phone.OAuthPhoneRiskException;
import com.example.temperate.service.auth.oauth.phone.OAuthPhoneRiskService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * 使用 Redis Lua 原子执行 OAuth 手机发送与冲突探测风控，并同时写入流程主体和全局设备两小时封禁。
 *
 * <p>五分钟窗口最多五次有效发送；任何六十秒冷却绕过立即封禁。Redis 异常统一 Fail Closed，调用方不得
 * 在未取得 ALLOWED 结果时继续调用 RabbitMQ 或短信供应商。</p>
 */
@Service
public final class OAuthPhoneRiskServiceImpl implements OAuthPhoneRiskService {

    private static final long WINDOW_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final long COOLDOWN_MILLIS = Duration.ofSeconds(60).toMillis();
    private static final long BLOCK_SECONDS = Duration.ofHours(2).toSeconds();
    private static final int MAX_REQUESTS = 5;
    private static final RedisScript<Long> CHECK_SEND = script(
            "check_oauth_phone_send_risk.lua");
    private static final RedisScript<Long> RECORD_CONFLICT = script(
            "record_oauth_phone_conflict.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;

    public OAuthPhoneRiskServiceImpl(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
    }

    @Override
    public void requireSendAllowed(ProtectedOAuthFlowAccess access, Instant now) {
        long result = execute(
                CHECK_SEND,
                access,
                now,
                Long.toString(WINDOW_MILLIS),
                Long.toString(COOLDOWN_MILLIS),
                Integer.toString(MAX_REQUESTS),
                Long.toString(BLOCK_SECONDS));
        if (result != 0L) {
            throw blocked();
        }
    }

    @Override
    public void recordPhoneConflict(ProtectedOAuthFlowAccess access, Instant now) {
        long result = execute(
                RECORD_CONFLICT,
                access,
                now,
                Long.toString(WINDOW_MILLIS),
                Integer.toString(MAX_REQUESTS),
                Long.toString(BLOCK_SECONDS));
        if (result == 2L) {
            throw blocked();
        }
    }

    private long execute(
            RedisScript<Long> script,
            ProtectedOAuthFlowAccess access,
            Instant now,
            String... arguments) {
        Objects.requireNonNull(access);
        Object[] values = new Object[1 + arguments.length];
        values[0] = Long.toString(now.toEpochMilli());
        System.arraycopy(arguments, 0, values, 1, arguments.length);
        try {
            Long result = redisTemplate.execute(
                    script,
                    List.of(
                            script == CHECK_SEND
                                    ? keyFactory.oauthPhoneSendRiskKey(access.flowId())
                                    : keyFactory.oauthPhoneConflictRiskKey(access.flowId()),
                            keyFactory.oauthPhoneBlockKey(access.flowId()),
                            keyFactory.globalDeviceBlockKey(access.globalDeviceId())),
                    values);
            if (result == null) {
                throw blocked();
            }
            return result;
        } catch (OAuthPhoneRiskException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OAuthPhoneRiskException(
                    "OAuth phone verification is temporarily unavailable.", exception);
        }
    }

    private static OAuthPhoneRiskException blocked() {
        return new OAuthPhoneRiskException(
                "OAuth phone verification is temporarily blocked.");
    }

    private static RedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/auth-oauth/" + name));
        script.setResultType(Long.class);
        return script;
    }
}
