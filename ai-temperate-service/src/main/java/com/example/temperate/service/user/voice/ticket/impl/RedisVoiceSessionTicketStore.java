package com.example.temperate.service.user.voice.ticket.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.user.voice.VoiceErrorCode;
import com.example.temperate.service.user.voice.VoiceException;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketSnapshot;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 使用 Redis Lua 原子执行语音票据双维度限流、创建和单次消费。
 *
 * <p>Key 只包含用途隔离 HMAC；Redis 故障统一 Fail Closed，避免客户端绕过票据直接占用本机 GPU。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
public final class RedisVoiceSessionTicketStore implements VoiceSessionTicketStore {

    private static final RedisScript<Long> CREATE = script(
            "create_voice_session_ticket.lua",
            Long.class);
    private static final RedisScript<String> CONSUME = script(
            "consume_voice_session_ticket.lua",
            String.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final ObjectMapper objectMapper;

    public RedisVoiceSessionTicketStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            ObjectMapper objectMapper) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public void create(
            HmacIdentifier ticketHash,
            HmacIdentifier userRateHash,
            HmacIdentifier deviceRateHash,
            VoiceSessionTicketSnapshot snapshot,
            Duration ticketTtl,
            Duration rateWindow,
            int rateLimit) {
        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw unavailable(exception);
        }
        Long status = execute(
                CREATE,
                List.of(
                        keyFactory.voiceTicketUserRateKey(userRateHash),
                        keyFactory.voiceTicketDeviceRateKey(deviceRateHash),
                        keyFactory.voiceSessionTicketKey(ticketHash)),
                serialized,
                Long.toString(ticketTtl.toMillis()),
                Long.toString(rateWindow.toMillis()),
                Integer.toString(rateLimit));
        if (status == 1L) {
            throw new VoiceException(
                    VoiceErrorCode.VOICE_TICKET_RATE_LIMITED,
                    "语音连接请求过于频繁，请稍后再试。",
                    true);
        }
        if (status != 0L) {
            throw unavailable(null);
        }
    }

    @Override
    public Optional<VoiceSessionTicketSnapshot> consume(HmacIdentifier ticketHash) {
        String serialized = executeNullable(
                CONSUME,
                List.of(keyFactory.voiceSessionTicketKey(ticketHash)));
        if (serialized == null || serialized.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(
                    serialized,
                    VoiceSessionTicketSnapshot.class));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            // v1 或损坏快照已经被原子删除；按无效 Ticket 收敛，不能恢复或重新写回。
            return Optional.empty();
        }
    }

    private <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
        T result = executeNullable(script, keys, args);
        if (result == null) {
            throw unavailable(null);
        }
        return result;
    }

    private <T> T executeNullable(
            RedisScript<T> script,
            List<String> keys,
            Object... args) {
        try {
            return redisTemplate.execute(script, keys, args);
        } catch (VoiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private static VoiceException unavailable(Throwable cause) {
        return new VoiceException(
                VoiceErrorCode.VOICE_INFRASTRUCTURE_UNAVAILABLE,
                "语音连接服务暂时不可用。",
                true,
                cause);
    }

    private static <T> RedisScript<T> script(String name, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/voice/" + name));
        script.setResultType(resultType);
        return script;
    }
}
