package com.example.temperate.service.user.membership.payment.refund;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.redis.key.PaymentCallbackRedisId;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.membership.payment.refund.impl.RedisPaymentRefundCoordinationStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 该集成测试是来在真实 Redis 中验证退款尝试单调、待发布补发、旧消息去重和二十四小时协调 TTL。
 */
@Testcontainers(disabledWithoutDocker = true)
class PaymentRefundCoordinationRedisIntegrationTest {

    private static final String REDIS_IMAGE =
            System.getenv().getOrDefault("AIT_TEST_REDIS_IMAGE", "redis:7.4.2-alpine");
    private static final HybridBase64UrlCodec ID_CODEC = new HybridBase64UrlCodec();
    private static final RedisKeyFactory KEYS = new RedisKeyFactory("test");
    private static final String CALLBACK_ID = id((byte) 41);
    private static final String RETRY_MESSAGE_ID = id((byte) 42);
    private static final String TERMINAL_MESSAGE_ID = id((byte) 43);

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private RedisPaymentRefundCoordinationStore store;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        store = new RedisPaymentRefundCoordinationStore(
                redisTemplate, KEYS, ID_CODEC);
    }

    @Test
    void pendingRetryIsRepublishedWithoutReclaimingProviderAttempt() {
        assertThat(store.beginInitial(CALLBACK_ID).action())
                .isEqualTo(PaymentRefundCoordinationAction.ATTEMPT_PROVIDER);
        assertThat(store.prepareRetry(
                CALLBACK_ID, 1, RETRY_MESSAGE_ID, 2, "LIUHAO_TIMEOUT"))
                .isTrue();

        PaymentRefundCoordinationDecision pending = store.beginInitial(CALLBACK_ID);

        assertThat(pending.action()).isEqualTo(PaymentRefundCoordinationAction.PUBLISH_RETRY);
        assertThat(pending.messageId()).isEqualTo(RETRY_MESSAGE_ID);
        assertThat(pending.nextAttemptNo()).isEqualTo(2);
        assertThat(store.confirmRetry(CALLBACK_ID, RETRY_MESSAGE_ID, 2)).isTrue();
        assertThat(store.claimRetry(CALLBACK_ID, 2, RETRY_MESSAGE_ID).action())
                .isEqualTo(PaymentRefundCoordinationAction.ATTEMPT_PROVIDER);
    }

    @Test
    void retryRedeliveryAfterNextPublishFailureOnlyReturnsPendingPublish() {
        store.beginInitial(CALLBACK_ID);
        store.prepareRetry(CALLBACK_ID, 1, RETRY_MESSAGE_ID, 2, "LIUHAO_TIMEOUT");
        store.confirmRetry(CALLBACK_ID, RETRY_MESSAGE_ID, 2);
        store.claimRetry(CALLBACK_ID, 2, RETRY_MESSAGE_ID);
        String nextMessageId = id((byte) 44);
        store.prepareRetry(CALLBACK_ID, 2, nextMessageId, 3, "LIUHAO_TIMEOUT");

        PaymentRefundCoordinationDecision redelivery =
                store.claimRetry(CALLBACK_ID, 2, RETRY_MESSAGE_ID);

        assertThat(redelivery.action())
                .isEqualTo(PaymentRefundCoordinationAction.PUBLISH_RETRY);
        assertThat(redelivery.messageId()).isEqualTo(nextMessageId);
        assertThat(redelivery.nextAttemptNo()).isEqualTo(3);
    }

    @Test
    void terminalStateCannotReturnToAttempting() {
        store.beginInitial(CALLBACK_ID);
        assertThat(store.prepareTerminal(
                CALLBACK_ID,
                1,
                TERMINAL_MESSAGE_ID,
                PaymentRefundTerminalOutcome.EXPLICIT_FAILURE,
                "LIUHAO_SIGNATURE_INVALID")).isTrue();
        assertThat(store.confirmTerminal(CALLBACK_ID, TERMINAL_MESSAGE_ID)).isTrue();

        assertThat(store.beginInitial(CALLBACK_ID).action())
                .isEqualTo(PaymentRefundCoordinationAction.COMPLETE_COORDINATED);
        assertThat(store.markSucceeded(CALLBACK_ID, 1)).isFalse();
    }

    @Test
    void coordinationStateUsesBoundedTwentyFourHourTtl() {
        store.beginInitial(CALLBACK_ID);
        String key = KEYS.paymentRefundCoordinationKey(
                new PaymentCallbackRedisId(CALLBACK_ID));

        Long ttlMillis = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);

        assertThat(ttlMillis)
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofHours(24).toMillis());
    }

    private static String id(byte value) {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, value);
        return ID_CODEC.encode(bytes);
    }
}
