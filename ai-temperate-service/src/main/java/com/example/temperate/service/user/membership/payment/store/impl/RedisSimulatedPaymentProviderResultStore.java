package com.example.temperate.service.user.membership.payment.store.impl;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderResult;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.store.SimulatedPaymentProviderResultStore;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 该实现是来保存模拟支付方的有界查询结果 Hash；回调入队时同一 Hash 会在 Lua 中原子更新为 PAID。
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment.simulator",
        name = "enabled",
        havingValue = "true")
public final class RedisSimulatedPaymentProviderResultStore
        implements SimulatedPaymentProviderResultStore {

    private static final long TTL_MILLIS = Duration.ofHours(6).toMillis();
    private static final RedisScript<Long> PUT = longScript("put_provider_result.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;

    public RedisSimulatedPaymentProviderResultStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
    }

    @Override
    public void initializeUnpaid(String orderId, OffsetDateTime now) {
        write(new SimulatedPaymentProviderResult(
                SimulatedPaymentProviderResult.CURRENT_SCHEMA_VERSION,
                new MembershipOrderRedisId(orderId).value(),
                SimulatedPaymentProviderStatus.UNPAID,
                null,
                null,
                null,
                null,
                Objects.requireNonNull(now)),
                "CREATE_IF_MISSING");
    }

    @Override
    public void put(SimulatedPaymentProviderResult result) {
        write(result, "REPLACE");
    }

    private void write(SimulatedPaymentProviderResult result, String mode) {
        SimulatedPaymentProviderResult valid = Objects.requireNonNull(result);
        Map<String, String> fields = MembershipPaymentRedisCodec.writeProvider(valid);
        List<Object> arguments = new ArrayList<>();
        arguments.add(mode);
        arguments.add(Long.toString(TTL_MILLIS));
        arguments.add(Integer.toString(fields.size()));
        fields.forEach((name, value) -> {
            arguments.add(name);
            arguments.add(value);
        });
        long written = execute(
                List.of(keyFactory.simulatedPaymentProviderResultKey(
                        new MembershipOrderRedisId(valid.orderId()))),
                arguments.toArray());
        if (written != 0L && written != 1L) {
            throw unavailable("Redis simulated provider result was not written.");
        }
    }

    @Override
    public Optional<SimulatedPaymentProviderResult> find(String orderId) {
        String key = keyFactory.simulatedPaymentProviderResultKey(
                new MembershipOrderRedisId(orderId));
        try {
            Map<Object, Object> raw = redisTemplate.opsForHash().entries(key);
            return raw.isEmpty()
                    ? Optional.empty()
                    : Optional.of(MembershipPaymentRedisCodec.readProvider(
                            MembershipPaymentRedisCodec.stringMap(raw)));
        } catch (RuntimeException exception) {
            throw unavailable("Redis simulated provider result read failed.", exception);
        }
    }

    private long execute(List<String> keys, Object... arguments) {
        try {
            Long result = redisTemplate.execute(PUT, keys, arguments);
            if (result == null) {
                throw unavailable("Redis simulated provider result script returned no result.");
            }
            return result;
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis simulated provider result write failed.", exception);
        }
    }

    private static RedisScript<Long> longScript(String fileName) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/membership-payment/" + fileName));
        script.setResultType(Long.class);
        return script;
    }

    private static MembershipPaymentInfrastructureException unavailable(String message) {
        return new MembershipPaymentInfrastructureException(message);
    }

    private static MembershipPaymentInfrastructureException unavailable(
            String message,
            Throwable cause) {
        return new MembershipPaymentInfrastructureException(message, cause);
    }
}
