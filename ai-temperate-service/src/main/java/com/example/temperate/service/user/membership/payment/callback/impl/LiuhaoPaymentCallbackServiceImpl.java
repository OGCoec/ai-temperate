package com.example.temperate.service.user.membership.payment.callback.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.callback.LiuhaoPaymentCallbackCommand;
import com.example.temperate.service.user.membership.payment.callback.LiuhaoPaymentCallbackService;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackEnqueueResult;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackSnapshot;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentLifecycleDiagnostics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTraceContext;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptTransactionService;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentOrderLookupService;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProviderRegistry;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderReference;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.provider.liuhao.LiuhaoPaymentSignatureService;
import com.example.temperate.service.user.membership.payment.provider.liuhao.LiuhaoSignatureVerificationResult;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.MembershipProviderTradeNoPatchOutcome;
import com.example.temperate.service.user.membership.payment.store.PaymentCallbackQueue;
import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来按固定安全顺序验证六号易支付 V2 通知，并在主动查询确认付款后复用原回调队列。
 *
 * <p>它不增加回调字段、不直接迁移订单状态，也不把 RSA 签名、完整参数或支付载体写入缓存和日志。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment.liuhao",
        name = "enabled",
        havingValue = "true")
public final class LiuhaoPaymentCallbackServiceImpl implements LiuhaoPaymentCallbackService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(LiuhaoPaymentCallbackServiceImpl.class);
    private static final String SUCCESS = "TRADE_SUCCESS";
    private static final Set<String> PAY_TYPES = Set.of("alipay", "wxpay");
    private static final Pattern SAFE_TRADE = Pattern.compile("^[A-Za-z0-9._:-]{1,112}$");

    private final MembershipPaymentOrderLookupService lookupService;
    private final MembershipPaymentAttemptTransactionService transactionService;
    private final MembershipPaymentProviderRegistry providerRegistry;
    private final MembershipOrderSnapshotStore orderStore;
    private final PaymentCallbackQueue callbackQueue;
    private final LiuhaoPaymentSignatureService signatures;
    private final HybridSemaphoreIdWorker idWorker;
    private final HybridBase64UrlCodec base64UrlCodec;
    private final MembershipPaymentProperties properties;
    private final Clock clock;
    private final MembershipPaymentMetrics metrics;

    public LiuhaoPaymentCallbackServiceImpl(
            MembershipPaymentOrderLookupService lookupService,
            MembershipPaymentAttemptTransactionService transactionService,
            MembershipPaymentProviderRegistry providerRegistry,
            MembershipOrderSnapshotStore orderStore,
            PaymentCallbackQueue callbackQueue,
            LiuhaoPaymentSignatureService signatures,
            HybridSemaphoreIdWorker idWorker,
            HybridBase64UrlCodec base64UrlCodec,
            MembershipPaymentProperties properties,
            Clock clock,
            MembershipPaymentMetrics metrics) {
        this.lookupService = Objects.requireNonNull(lookupService);
        this.transactionService = Objects.requireNonNull(transactionService);
        this.providerRegistry = Objects.requireNonNull(providerRegistry);
        this.orderStore = Objects.requireNonNull(orderStore);
        this.callbackQueue = Objects.requireNonNull(callbackQueue);
        this.signatures = Objects.requireNonNull(signatures);
        this.idWorker = Objects.requireNonNull(idWorker);
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public boolean receive(LiuhaoPaymentCallbackCommand command) {
        try {
            return receiveValidated(Objects.requireNonNull(command));
        } catch (MembershipPaymentException exception) {
            metrics.callbackRejected();
            throw exception;
        }
    }

    private boolean receiveValidated(LiuhaoPaymentCallbackCommand command) {
        OffsetDateTime receivedAt = MembershipPaymentTime.now(clock);
        Map<String, String> signedFields = command.externalFields();
        // 验签必须排在时间、商户、订单和金额读取之前，不能让未认证输入进入业务分支。
        LiuhaoSignatureVerificationResult verification =
                signatures.verifyDetailed(signedFields);
        if (!verification.verified()) {
            LOGGER.warn(
                    "Liuhao membership payment callback signature rejected; traceId={} reason={}",
                    MembershipPaymentTraceContext.currentTraceId(),
                    verification.reason().name().toLowerCase(Locale.ROOT));
            throw failure(
                    MembershipPaymentErrorCode.LIUHAO_SIGNATURE_INVALID,
                    "Liuhao callback signature is invalid.");
        }
        long requestTimestamp = requireTimestamp(command.timestamp(), receivedAt.toInstant());
        if (!properties.liuhao().pid().equals(command.pid())) {
            throw failure(
                    MembershipPaymentErrorCode.LIUHAO_AUTH_FAILED,
                    "Liuhao callback merchant is invalid.");
        }
        String orderId = canonicalOrderId(command.outTradeNo());
        String rawTradeNo = requiredTrade(command.tradeNo());
        String taggedTradeNo = PaymentProviderReference.trade(
                PaymentProviderType.LIUHAO, rawTradeNo);
        BigDecimal amount = requireAmount(command.money());
        if (!PAY_TYPES.contains(command.type()) || !SUCCESS.equals(command.tradeStatus())) {
            throw invalid("Liuhao callback payment fields are invalid.");
        }

        MembershipOrderSnapshot order = lookupService.find(orderId).orElseThrow(() ->
                failure(
                        MembershipPaymentErrorCode.MEMBERSHIP_ORDER_NOT_FOUND,
                        "The membership order was not found."));
        if ((order.providerTradeNo() != null
                        && (!PaymentProviderReference.isTrade(
                                        PaymentProviderType.LIUHAO, order.providerTradeNo())
                                || !order.providerTradeNo().equals(taggedTradeNo)))
                || order.payAmountYuan().compareTo(amount) != 0
                || !order.payType().equals(command.type())
                ) {
            throw failure(
                    MembershipPaymentErrorCode.LIUHAO_ORDER_CONFLICT,
                    "Liuhao callback does not match the local order.");
        }
        MembershipPaymentProvider provider =
                providerRegistry.getRequired(PaymentProviderType.LIUHAO);
        PaymentQueryResult query = provider.queryPayment(
                new PaymentQueryCommand(orderId, taggedTradeNo));
        if (query.status() != PaymentProviderStatus.PAID
                || !Objects.equals(query.orderId(), orderId)
                || !Objects.equals(query.providerTradeNo(), taggedTradeNo)
                || query.amountYuan() == null
                || query.amountYuan().compareTo(amount) != 0
                || query.finishedAt() == null) {
            throw failure(
                    MembershipPaymentErrorCode.LIUHAO_RESPONSE_INVALID,
                    "Liuhao query did not confirm the callback payment fact.");
        }
        // 查询接口缺少完成时间时客户端使用验签响应时刻；它可能晚于回调入口时刻，因此收敛到回调接收时刻以满足既有事实时序。
        OffsetDateTime paidAt = query.finishedAt().isAfter(receivedAt)
                ? receivedAt
                : query.finishedAt();
        // 回调入口已经确定 Provider；验签与主动查询均确认后，才允许 NULL 原子绑定为真实 TRADE 引用。
        if (order.providerTradeNo() == null) {
            bindCallbackReference(order, orderId, taggedTradeNo, receivedAt);
        }

        String canonical = signatures.canonicalize(signedFields);
        HmacIdentifier fingerprint = signatures.identify("LIUHAO_CALLBACK", canonical);
        HmacIdentifier tradeFingerprint = signatures.identify(
                "LIUHAO_PROVIDER_TRADE", properties.liuhao().pid() + "\n" + rawTradeNo);
        PaymentCallbackSnapshot snapshot = new PaymentCallbackSnapshot(
                PaymentCallbackSnapshot.CURRENT_SCHEMA_VERSION,
                base64UrlCodec.encode(idWorker.nextId()),
                orderId,
                properties.liuhao().pid(),
                taggedTradeNo,
                query.channelTradeNo() == null ? rawTradeNo : query.channelTradeNo(),
                command.type(),
                SUCCESS,
                amount,
                paidAt,
                receivedAt,
                requestTimestamp,
                fingerprint.value(),
                signatures.payloadDigest(signedFields));
        try {
            PaymentCallbackEnqueueResult result = callbackQueue.enqueue(
                    snapshot, fingerprint, tradeFingerprint);
            metrics.callbackReceived(!result.enqueued());
            return !result.enqueued();
        } catch (MembershipPaymentInfrastructureException exception) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_REDIS_UNAVAILABLE,
                    "Payment callback state is temporarily unavailable.",
                    exception);
        }
    }

    private long requireTimestamp(String raw, Instant receivedAt) {
        try {
            String value = requiredText(raw, 10);
            long seconds = Long.parseLong(value);
            Duration skew = Duration.between(Instant.ofEpochSecond(seconds), receivedAt).abs();
            if (value.length() != 10
                    || !Long.toString(seconds).equals(value)
                    || skew.compareTo(properties.liuhao().timestampTolerance()) > 0) {
                throw invalid("Liuhao callback timestamp is outside the accepted window.");
            }
            return seconds;
        } catch (NumberFormatException | DateTimeException exception) {
            throw invalid("Liuhao callback timestamp is invalid.");
        }
    }

    private void bindCallbackReference(
            MembershipOrderSnapshot order,
            String orderId,
            String taggedTradeNo,
            OffsetDateTime receivedAt) {
        transactionService.bindProviderTradeNo(
                order.loginIdentityId(), base64UrlCodec.decode(orderId), taggedTradeNo);
        try {
            MembershipProviderTradeNoPatchOutcome patch = Objects.requireNonNull(
                    orderStore.patchProviderTradeNo(
                            orderId, order.loginIdentityId(), taggedTradeNo));
            if (patch == MembershipProviderTradeNoPatchOutcome.CONFLICT) {
                throw failure(
                        MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_PROVIDER_TRADE_CONFLICT,
                        "The provider trade number conflicts with the current order.");
            }
            if (patch == MembershipProviderTradeNoPatchOutcome.MISSING) {
                // 数据库已绑定而 Redis Key 恰好缺失时，以同版本完整快照恢复；更高版本并发事实仍由 Lua 拒绝覆盖。
                MembershipOrderSnapshot restored = orderStore.putAndGet(
                        withProviderTradeNo(order, taggedTradeNo));
                if (!Objects.equals(restored.providerTradeNo(), taggedTradeNo)) {
                    throw failure(
                            MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_PROVIDER_TRADE_CONFLICT,
                            "The provider trade number conflicts with the current order.");
                }
            }
            String redisBind = patch == MembershipProviderTradeNoPatchOutcome.UNCHANGED
                    ? "unchanged" : "applied";
            MembershipPaymentLifecycleDiagnostics.referenceBound(
                    order,
                    PaymentProviderType.LIUHAO,
                    "callback",
                    "applied",
                    redisBind,
                    receivedAt,
                    MembershipPaymentTraceContext.currentTraceId(),
                    "unavailable");
        } catch (MembershipPaymentInfrastructureException exception) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_REDIS_UNAVAILABLE,
                    "Payment callback state is temporarily unavailable.",
                    exception);
        }
    }

    private static BigDecimal requireAmount(String raw) {
        try {
            BigDecimal amount = new BigDecimal(requiredText(raw, 32));
            if (amount.signum() <= 0 || amount.scale() > 2) {
                throw invalid("Liuhao callback amount is invalid.");
            }
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException | ArithmeticException exception) {
            throw invalid("Liuhao callback amount is invalid.");
        }
    }

    private static String canonicalOrderId(String raw) {
        try {
            return new MembershipOrderRedisId(raw).value();
        } catch (IllegalArgumentException exception) {
            throw invalid("Liuhao callback order ID is invalid.");
        }
    }

    private static String requiredTrade(String value) {
        String trade = requiredText(value, 112);
        if (!SAFE_TRADE.matcher(trade).matches()) {
            throw invalid("Liuhao callback trade number is invalid.");
        }
        return trade;
    }

    private static MembershipOrderSnapshot withProviderTradeNo(
            MembershipOrderSnapshot source,
            String providerTradeNo) {
        return new MembershipOrderSnapshot(
                source.schemaVersion(),
                source.orderId(),
                source.loginIdentityId(),
                source.membershipTier(),
                source.payAmountYuan(),
                source.payType(),
                source.status(),
                source.idempotencyKey(),
                providerTradeNo,
                source.paymentStartedAt(),
                source.expiresAt(),
                source.closingDeadlineAt(),
                source.paidAt(),
                source.stateVersion(),
                source.createdAt(),
                source.updatedAt());
    }

    private static String requiredText(String value, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength
                || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid("Liuhao callback text field is invalid.");
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
