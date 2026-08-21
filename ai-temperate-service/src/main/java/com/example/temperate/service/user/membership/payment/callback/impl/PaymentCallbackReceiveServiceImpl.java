package com.example.temperate.service.user.membership.payment.callback.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackEnqueueResult;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackFingerprintService;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackReceiveService;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackSnapshot;
import com.example.temperate.service.user.membership.payment.callback.SimulatedLiuhaoCallbackCommand;
import com.example.temperate.service.user.membership.payment.callback.SimulatedLiuhaoCallbackResult;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.store.PaymentCallbackQueue;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来校验模拟回调商户、时间窗和字段语法，生成回调 Base64URL 后仅通过一个 Lua 调用写入 Redis ready 队列。
 *
 * <p>该类有意不依赖任何 Mapper；订单存在性、金额和支付方式一致性由第一个五秒批任务批量校验。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment.simulator",
        name = "enabled",
        havingValue = "true")
public final class PaymentCallbackReceiveServiceImpl
        implements PaymentCallbackReceiveService {

    private static final String SUCCESS = "TRADE_SUCCESS";
    private static final Set<String> PAY_TYPES = Set.of("alipay", "wxpay");
    private static final Pattern SAFE_TOKEN = Pattern.compile("^[A-Za-z0-9._:-]+$");
    private static final DateTimeFormatter LIUHAO_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PaymentCallbackQueue callbackQueue;
    private final PaymentCallbackFingerprintService fingerprintService;
    private final HybridSemaphoreIdWorker idWorker;
    private final HybridBase64UrlCodec base64UrlCodec;
    private final MembershipPaymentProperties.Simulator simulator;
    private final Clock clock;
    private final MembershipPaymentMetrics metrics;

    public PaymentCallbackReceiveServiceImpl(
            PaymentCallbackQueue callbackQueue,
            PaymentCallbackFingerprintService fingerprintService,
            HybridSemaphoreIdWorker idWorker,
            HybridBase64UrlCodec base64UrlCodec,
            MembershipPaymentProperties properties,
            Clock clock,
            MembershipPaymentMetrics metrics) {
        this.callbackQueue = Objects.requireNonNull(callbackQueue);
        this.fingerprintService = Objects.requireNonNull(fingerprintService);
        this.idWorker = Objects.requireNonNull(idWorker);
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
        this.simulator = Objects.requireNonNull(properties).simulator();
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    /**
     * 校验成功后不查询数据库；Lua 的短期重复与首次入队都表示支付方可以停止重试并收到 success。
     */
    @Override
    public SimulatedLiuhaoCallbackResult receive(
            SimulatedLiuhaoCallbackCommand command) {
        try {
            return receiveValidated(command);
        } catch (MembershipPaymentException exception) {
            if (exception.code() == MembershipPaymentErrorCode.INPUT_INVALID) {
                metrics.callbackRejected();
            }
            throw exception;
        }
    }

    private SimulatedLiuhaoCallbackResult receiveValidated(
            SimulatedLiuhaoCallbackCommand command) {
        SimulatedLiuhaoCallbackCommand valid = validate(command);
        OffsetDateTime receivedAt = OffsetDateTime.ofInstant(
                clock.instant(), ZoneOffset.UTC);
        long requestTimestamp = parseEpochSecond(valid.timestamp());
        requireTimestampWindow(requestTimestamp, receivedAt.toInstant());
        OffsetDateTime addTime = parseLiuhaoTime(valid.addTime(), "addtime");
        OffsetDateTime paidAt = parseLiuhaoTime(valid.endTime(), "endtime");
        if (paidAt.isBefore(addTime)) {
            throw invalid("Callback endtime cannot be before addtime.");
        }
        BigDecimal amount = parseAmount(valid.money());
        String orderId = canonicalOrderId(valid.outTradeNo());
        String callbackId = base64UrlCodec.encode(idWorker.nextId());
        HmacIdentifier fingerprint = fingerprintService.fingerprint(valid);
        PaymentCallbackSnapshot snapshot = new PaymentCallbackSnapshot(
                PaymentCallbackSnapshot.CURRENT_SCHEMA_VERSION,
                callbackId,
                orderId,
                valid.pid(),
                valid.tradeNo(),
                valid.apiTradeNo(),
                valid.type(),
                valid.tradeStatus(),
                amount,
                paidAt,
                receivedAt,
                requestTimestamp,
                fingerprint.value(),
                fingerprintService.payloadDigest(valid));
        try {
            PaymentCallbackEnqueueResult result = callbackQueue.enqueue(
                    snapshot,
                    fingerprint,
                    fingerprintService.providerTradeFingerprint(valid));
            SimulatedLiuhaoCallbackResult response = new SimulatedLiuhaoCallbackResult(
                    result.callbackId(), !result.enqueued());
            metrics.callbackReceived(response.duplicate());
            return response;
        } catch (MembershipPaymentInfrastructureException exception) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_REDIS_UNAVAILABLE,
                    "Payment callback state is temporarily unavailable.",
                    exception);
        }
    }

    private SimulatedLiuhaoCallbackCommand validate(
            SimulatedLiuhaoCallbackCommand command) {
        SimulatedLiuhaoCallbackCommand value = Objects.requireNonNull(command);
        if (!Objects.equals(simulator.pid(), value.pid())) {
            throw invalid("Callback merchant does not match.");
        }
        requireSafeToken(value.tradeNo(), "trade_no", 128);
        requireSafeToken(value.apiTradeNo(), "api_trade_no", 128);
        requireSafeToken(value.type(), "type", 16);
        if (!PAY_TYPES.contains(value.type())) {
            throw invalid("Callback payment type is unsupported.");
        }
        if (!SUCCESS.equals(value.tradeStatus())) {
            throw invalid("Callback trade status is unsupported.");
        }
        requireText(value.name(), "name", 128, false);
        requireText(value.param(), "param", 256, true);
        requireText(value.buyer(), "buyer", 256, true);
        requireText(value.sign(), "sign", 1024, false);
        if (!"RSA".equals(value.signType())) {
            throw invalid("Callback sign_type must be RSA.");
        }
        if (simulator.signatureValidationEnabled()) {
            String expected = fingerprintService.fingerprint(value).value();
            boolean matches = MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    value.sign().getBytes(StandardCharsets.UTF_8));
            if (!matches) {
                throw invalid("Callback signature is invalid.");
            }
        }
        return value;
    }

    private String canonicalOrderId(String value) {
        try {
            return new MembershipOrderRedisId(value).value();
        } catch (IllegalArgumentException exception) {
            throw invalid("Callback order ID is invalid.");
        }
    }

    private void requireTimestampWindow(long requestTimestamp, Instant receivedAt) {
        Instant requestTime;
        try {
            requestTime = Instant.ofEpochSecond(requestTimestamp);
        } catch (RuntimeException exception) {
            throw invalid("Callback timestamp is invalid.");
        }
        Duration skew = Duration.between(requestTime, receivedAt).abs();
        if (skew.compareTo(simulator.timestampTolerance()) > 0) {
            throw invalid("Callback timestamp is outside the accepted window.");
        }
    }

    private static long parseEpochSecond(String value) {
        requireText(value, "timestamp", 20, false);
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0L || !Long.toString(parsed).equals(value)) {
                throw invalid("Callback timestamp is not canonical.");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid("Callback timestamp is invalid.");
        }
    }

    private static OffsetDateTime parseLiuhaoTime(String value, String name) {
        requireText(value, name, 40, false);
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                long seconds = Long.parseLong(value);
                if (seconds > 0L && Long.toString(seconds).equals(value)) {
                    return OffsetDateTime.ofInstant(
                            Instant.ofEpochSecond(seconds), ZoneOffset.UTC);
                }
            } catch (RuntimeException ignored) {
                throw invalid("Callback " + name + " is invalid.");
            }
        }
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value, LIUHAO_TIME).atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException exception) {
                throw invalid("Callback " + name + " is invalid.");
            }
        }
    }

    private static BigDecimal parseAmount(String value) {
        requireText(value, "money", 32, false);
        try {
            BigDecimal amount = new BigDecimal(value);
            if (amount.signum() < 0 || !amount.toPlainString().equals(value)) {
                throw invalid("Callback money is invalid.");
            }
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid("Callback money is invalid.");
        }
    }

    private static void requireSafeToken(String value, String name, int maxLength) {
        requireText(value, name, maxLength, false);
        if (!SAFE_TOKEN.matcher(value).matches()) {
            throw invalid("Callback " + name + " contains unsupported characters.");
        }
    }

    private static void requireText(
            String value,
            String name,
            int maxLength,
            boolean allowEmpty) {
        if (value == null
                || value.length() > maxLength
                || !value.equals(value.trim())
                || (!allowEmpty && value.isEmpty())
                || value.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw invalid("Callback " + name + " is invalid.");
        }
    }

    private static MembershipPaymentException invalid(String message) {
        return new MembershipPaymentException(
                MembershipPaymentErrorCode.INPUT_INVALID,
                message);
    }
}
