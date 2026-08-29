package com.example.temperate.service.user.membership.payment.callback.impl;

import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.callback.BarPaymentCallbackCommand;
import com.example.temperate.service.user.membership.payment.callback.BarPaymentCallbackResult;
import com.example.temperate.service.user.membership.payment.callback.BarPaymentCallbackService;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackEnqueueResult;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackSnapshot;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptTransactionService;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentOrderLookupService;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProviderRegistry;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.provider.bar.BarPaymentSignatureService;
import com.example.temperate.service.user.membership.payment.store.PaymentCallbackQueue;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来按固定顺序验证 BAR 回调，并通过一次已签名主动查询取得可信 finished_at 后写入原有回调队列。
 *
 * <p>回调线程不执行订单状态更新或会员权益发放；查询或 Redis 入队失败时抛错，Web 层不得返回 success。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment.bar",
        name = "enabled",
        havingValue = "true")
public final class BarPaymentCallbackServiceImpl
        implements BarPaymentCallbackService {

    private static final String SUCCESS = "TRADE_SUCCESS";
    private static final String ORDER_NAME = "会员模拟支付订单";
    private static final String SIGN_TYPE = "HMAC-SHA256";
    private static final Duration TIMESTAMP_TOLERANCE = Duration.ofMinutes(5);
    private static final Set<String> PAY_TYPES = Set.of("alipay", "wxpay");
    private static final Pattern TRADE_NUMBER = Pattern.compile("^[0-9]{1,20}$");
    private static final Pattern SAFE_CHANNEL_TRADE =
            Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final MembershipPaymentOrderLookupService lookupService;
    private final MembershipPaymentAttemptTransactionService transactionService;
    private final MembershipPaymentProviderRegistry providerRegistry;
    private final PaymentCallbackQueue callbackQueue;
    private final BarPaymentSignatureService signatures;
    private final HybridSemaphoreIdWorker idWorker;
    private final HybridBase64UrlCodec base64UrlCodec;
    private final MembershipPaymentProperties properties;
    private final Clock clock;
    private final MembershipPaymentMetrics metrics;

    public BarPaymentCallbackServiceImpl(
            MembershipPaymentOrderLookupService lookupService,
            MembershipPaymentAttemptTransactionService transactionService,
            MembershipPaymentProviderRegistry providerRegistry,
            PaymentCallbackQueue callbackQueue,
            BarPaymentSignatureService signatures,
            HybridSemaphoreIdWorker idWorker,
            HybridBase64UrlCodec base64UrlCodec,
            MembershipPaymentProperties properties,
            Clock clock,
            MembershipPaymentMetrics metrics) {
        this.lookupService = Objects.requireNonNull(lookupService);
        this.transactionService = Objects.requireNonNull(transactionService);
        this.providerRegistry = Objects.requireNonNull(providerRegistry);
        this.callbackQueue = Objects.requireNonNull(callbackQueue);
        this.signatures = Objects.requireNonNull(signatures);
        this.idWorker = Objects.requireNonNull(idWorker);
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public BarPaymentCallbackResult receive(BarPaymentCallbackCommand command) {
        try {
            return receiveValidated(Objects.requireNonNull(command));
        } catch (MembershipPaymentException exception) {
            metrics.callbackRejected();
            throw exception;
        }
    }

    private BarPaymentCallbackResult receiveValidated(BarPaymentCallbackCommand command) {
        OffsetDateTime receivedAt = MembershipPaymentTime.now(clock);
        long requestTimestamp = requireTimestamp(command.timestamp(), receivedAt.toInstant());
        int keyVersion = requireKeyVersion(command.keyVersion());
        Map<String, String> signedFields = command.externalFields();
        if (!signatures.verify(signedFields, keyVersion)) {
            throw failure(
                    MembershipPaymentErrorCode.BAR_SIGNATURE_INVALID,
                    "BAR callback signature is invalid.");
        }
        requireCallbackMetadata(command);
        BigDecimal amount = requireAmount(command.money());
        String orderId = canonicalOrderId(command.outTradeNo());
        MembershipOrderSnapshot order = lookupService.find(orderId).orElseThrow(() ->
                failure(
                        MembershipPaymentErrorCode.MEMBERSHIP_ORDER_NOT_FOUND,
                        "The membership order was not found."));
        if (properties.defaultProvider() != PaymentProviderType.BAR
                || order.payAmountYuan().compareTo(amount) != 0
                || !order.payType().equals(command.type())
                || (order.providerTradeNo() != null
                        && !order.providerTradeNo().equals(command.tradeNo()))) {
            throw failure(
                    MembershipPaymentErrorCode.BAR_ORDER_CONFLICT,
                    "BAR callback does not match the local order.");
        }
        if (order.providerTradeNo() == null) {
            transactionService.bindProviderTradeNo(
                    order.loginIdentityId(),
                    base64UrlCodec.decode(orderId),
                    command.tradeNo());
        }

        MembershipPaymentProvider provider = providerRegistry.getRequired(PaymentProviderType.BAR);
        PaymentQueryResult query = provider.queryPayment(
                new PaymentQueryCommand(orderId, command.tradeNo()));
        if (query.status() != PaymentProviderStatus.PAID
                || !Objects.equals(query.orderId(), orderId)
                || !Objects.equals(query.providerTradeNo(), command.tradeNo())
                || !Objects.equals(query.channelTradeNo(), command.apiTradeNo())
                || query.amountYuan() == null
                || query.amountYuan().compareTo(amount) != 0
                || query.finishedAt() == null) {
            throw failure(
                    MembershipPaymentErrorCode.BAR_RESPONSE_INVALID,
                    "BAR query did not confirm the callback payment fact.");
        }

        String callbackId = base64UrlCodec.encode(idWorker.nextId());
        String canonical = signatures.canonicalize(signedFields);
        HmacIdentifier fingerprint = signatures.identify(
                keyVersion, "BAR_CALLBACK", canonical);
        HmacIdentifier providerTradeFingerprint = signatures.identify(
                keyVersion,
                "BAR_PROVIDER_TRADE",
                properties.bar().pid() + "\n" + command.tradeNo());
        PaymentCallbackSnapshot snapshot = new PaymentCallbackSnapshot(
                PaymentCallbackSnapshot.CURRENT_SCHEMA_VERSION,
                callbackId,
                orderId,
                properties.bar().pid(),
                command.tradeNo(),
                command.apiTradeNo(),
                command.type(),
                SUCCESS,
                amount,
                query.finishedAt(),
                receivedAt,
                requestTimestamp,
                fingerprint.value(),
                signatures.payloadDigest(signedFields));
        try {
            PaymentCallbackEnqueueResult result = callbackQueue.enqueue(
                    snapshot, fingerprint, providerTradeFingerprint);
            metrics.callbackReceived(!result.enqueued());
            return new BarPaymentCallbackResult(!result.enqueued());
        } catch (MembershipPaymentInfrastructureException exception) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_REDIS_UNAVAILABLE,
                    "Payment callback state is temporarily unavailable.",
                    exception);
        }
    }

    private void requireCallbackMetadata(BarPaymentCallbackCommand command) {
        if (!Objects.equals(properties.bar().pid(), command.pid())
                || !SUCCESS.equals(command.tradeStatus())
                || !SIGN_TYPE.equals(command.signType())
                || !ORDER_NAME.equals(command.name())
                || !PAY_TYPES.contains(command.type())
                || !TRADE_NUMBER.matcher(requiredText(command.tradeNo(), 20)).matches()
                || !SAFE_CHANNEL_TRADE.matcher(requiredText(command.apiTradeNo(), 128)).matches()
                || (command.param() != null && !command.param().isEmpty())) {
            throw failure(
                    MembershipPaymentErrorCode.INPUT_INVALID,
                    "BAR callback fields are invalid.");
        }
    }

    private int requireKeyVersion(String raw) {
        try {
            int version = Integer.parseInt(requiredText(raw, 10));
            if (version <= 0
                    || !Integer.toString(version).equals(raw)
                    || !properties.bar().apiKeys().containsKey(version)) {
                throw failure(
                        MembershipPaymentErrorCode.BAR_AUTH_FAILED,
                        "BAR callback key version is unavailable.");
            }
            return version;
        } catch (NumberFormatException exception) {
            throw failure(
                    MembershipPaymentErrorCode.BAR_AUTH_FAILED,
                    "BAR callback key version is invalid.");
        }
    }

    private static long requireTimestamp(String raw, Instant receivedAt) {
        try {
            String value = requiredText(raw, 10);
            long seconds = Long.parseLong(value);
            if (value.length() != 10 || !Long.toString(seconds).equals(value)) {
                throw invalid("BAR callback timestamp is not canonical.");
            }
            Duration skew = Duration.between(Instant.ofEpochSecond(seconds), receivedAt).abs();
            if (skew.compareTo(TIMESTAMP_TOLERANCE) > 0) {
                throw invalid("BAR callback timestamp is outside the accepted window.");
            }
            return seconds;
        } catch (NumberFormatException | DateTimeException exception) {
            throw invalid("BAR callback timestamp is invalid.");
        }
    }

    private static BigDecimal requireAmount(String raw) {
        try {
            BigDecimal amount = new BigDecimal(requiredText(raw, 32));
            if (amount.signum() <= 0 || amount.scale() != 2 || !amount.toPlainString().equals(raw)) {
                throw invalid("BAR callback amount is invalid.");
            }
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid("BAR callback amount is invalid.");
        }
    }

    private static String canonicalOrderId(String raw) {
        try {
            return new MembershipOrderRedisId(raw).value();
        } catch (IllegalArgumentException exception) {
            throw invalid("BAR callback order ID is invalid.");
        }
    }

    private static String requiredText(String value, int maximumLength) {
        if (value == null
                || value.isEmpty()
                || value.length() > maximumLength
                || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid("BAR callback text field is invalid.");
        }
        return value;
    }

    private static MembershipPaymentException invalid(String message) {
        return failure(MembershipPaymentErrorCode.INPUT_INVALID, message);
    }

    private static MembershipPaymentException failure(
            MembershipPaymentErrorCode code,
            String message) {
        return new MembershipPaymentException(code, message);
    }
}
