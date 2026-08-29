package com.example.temperate.service.user.membership.payment.callback.impl;

import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackEnqueueResult;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackSnapshot;
import com.example.temperate.service.user.membership.payment.callback.PaymentFactReconciliationService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.provider.bar.BarPaymentSignatureService;
import com.example.temperate.service.user.membership.payment.store.PaymentCallbackQueue;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来恢复本地模拟器已有 callbackId，或把 BAR 查询的签名支付事实转换为同一回调快照并幂等入队。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class PaymentFactReconciliationServiceImpl
        implements PaymentFactReconciliationService {

    private static final String SUCCESS = "TRADE_SUCCESS";

    private final PaymentCallbackQueue callbackQueue;
    private final ObjectProvider<BarPaymentSignatureService> barSignatures;
    private final HybridSemaphoreIdWorker idWorker;
    private final HybridBase64UrlCodec base64UrlCodec;
    private final MembershipPaymentProperties properties;
    private final Clock clock;

    public PaymentFactReconciliationServiceImpl(
            PaymentCallbackQueue callbackQueue,
            ObjectProvider<BarPaymentSignatureService> barSignatures,
            HybridSemaphoreIdWorker idWorker,
            HybridBase64UrlCodec base64UrlCodec,
            MembershipPaymentProperties properties,
            Clock clock) {
        this.callbackQueue = Objects.requireNonNull(callbackQueue);
        this.barSignatures = Objects.requireNonNull(barSignatures);
        this.idWorker = Objects.requireNonNull(idWorker);
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public boolean reconcilePaid(
            MembershipOrderSnapshot order,
            PaymentQueryResult paymentFact) {
        MembershipOrderSnapshot localOrder = Objects.requireNonNull(order);
        PaymentQueryResult fact = Objects.requireNonNull(paymentFact);
        if (fact.status() != PaymentProviderStatus.PAID) {
            return false;
        }
        if (fact.callbackId() != null) {
            return callbackQueue.ensureReady(fact.callbackId(), clock.millis());
        }
        if (properties.defaultProvider() != PaymentProviderType.BAR
                || !Objects.equals(localOrder.orderId(), fact.orderId())
                || fact.providerTradeNo() == null
                || fact.channelTradeNo() == null
                || fact.amountYuan() == null
                || fact.finishedAt() == null
                || localOrder.payAmountYuan().compareTo(fact.amountYuan()) != 0) {
            return false;
        }
        BarPaymentSignatureService signatures = barSignatures.getIfAvailable();
        if (signatures == null) {
            throw new IllegalStateException("BAR payment signature service is unavailable.");
        }
        int keyVersion = properties.bar().activeKeyVersion();
        OffsetDateTime receivedAt = MembershipPaymentTime.now(clock);
        Map<String, Object> canonicalFact = new LinkedHashMap<>();
        canonicalFact.put("pid", properties.bar().pid());
        canonicalFact.put("out_trade_no", fact.orderId());
        canonicalFact.put("trade_no", fact.providerTradeNo());
        canonicalFact.put("api_trade_no", fact.channelTradeNo());
        canonicalFact.put("money", fact.amountYuan().toPlainString());
        canonicalFact.put("finished_at", fact.finishedAt().toString());
        HmacIdentifier fingerprint = signatures.identify(
                keyVersion,
                "BAR_QUERY_PAYMENT_FACT",
                signatures.canonicalize(canonicalFact));
        HmacIdentifier providerTradeFingerprint = signatures.identify(
                keyVersion,
                "BAR_PROVIDER_TRADE",
                properties.bar().pid() + "\n" + fact.providerTradeNo());
        PaymentCallbackSnapshot snapshot = new PaymentCallbackSnapshot(
                PaymentCallbackSnapshot.CURRENT_SCHEMA_VERSION,
                base64UrlCodec.encode(idWorker.nextId()),
                localOrder.orderId(),
                properties.bar().pid(),
                fact.providerTradeNo(),
                fact.channelTradeNo(),
                localOrder.payType(),
                SUCCESS,
                fact.amountYuan(),
                fact.finishedAt(),
                receivedAt,
                receivedAt.toEpochSecond(),
                fingerprint.value(),
                signatures.payloadDigest(canonicalFact));
        PaymentCallbackEnqueueResult result = callbackQueue.enqueue(
                snapshot, fingerprint, providerTradeFingerprint);
        return result.enqueued() || result.callbackId() != null;
    }
}
