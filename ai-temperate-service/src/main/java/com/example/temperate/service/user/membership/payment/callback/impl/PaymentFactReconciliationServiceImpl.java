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
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderReference;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.provider.bar.BarPaymentSignatureService;
import com.example.temperate.service.user.membership.payment.provider.liuhao.LiuhaoPaymentSignatureService;
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
 * 该实现是来恢复本地模拟器已有 callbackId，或把 BAR/六号主动查询的已验签支付事实转换为同一回调快照并幂等入队。
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
    private final ObjectProvider<LiuhaoPaymentSignatureService> liuhaoSignatures;
    private final HybridSemaphoreIdWorker idWorker;
    private final HybridBase64UrlCodec base64UrlCodec;
    private final MembershipPaymentProperties properties;
    private final Clock clock;

    public PaymentFactReconciliationServiceImpl(
            PaymentCallbackQueue callbackQueue,
            ObjectProvider<BarPaymentSignatureService> barSignatures,
            ObjectProvider<LiuhaoPaymentSignatureService> liuhaoSignatures,
            HybridSemaphoreIdWorker idWorker,
            HybridBase64UrlCodec base64UrlCodec,
            MembershipPaymentProperties properties,
            Clock clock) {
        this.callbackQueue = Objects.requireNonNull(callbackQueue);
        this.barSignatures = Objects.requireNonNull(barSignatures);
        this.liuhaoSignatures = Objects.requireNonNull(liuhaoSignatures);
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
        PaymentProviderType providerType;
        try {
            providerType = PaymentProviderReference.resolveTrade(fact.providerTradeNo());
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if ((providerType != PaymentProviderType.BAR
                        && providerType != PaymentProviderType.LIUHAO)
                || !Objects.equals(localOrder.orderId(), fact.orderId())
                || fact.providerTradeNo() == null
                || (localOrder.providerTradeNo() != null
                        && !Objects.equals(
                                localOrder.providerTradeNo(), fact.providerTradeNo()))
                || fact.amountYuan() == null
                || fact.finishedAt() == null
                || localOrder.payAmountYuan().compareTo(fact.amountYuan()) != 0) {
            return false;
        }
        OffsetDateTime receivedAt = MembershipPaymentTime.now(clock);
        Map<String, Object> canonicalFact = new LinkedHashMap<>();
        canonicalFact.put("provider", providerType.name());
        canonicalFact.put("pid", providerPid(providerType));
        canonicalFact.put("out_trade_no", fact.orderId());
        canonicalFact.put("trade_no", fact.providerTradeNo());
        canonicalFact.put(
                "api_trade_no",
                fact.channelTradeNo() == null
                        ? PaymentProviderReference.rawTradeNo(fact.providerTradeNo())
                        : fact.channelTradeNo());
        canonicalFact.put("money", fact.amountYuan().toPlainString());
        canonicalFact.put("finished_at", fact.finishedAt().toString());
        HmacIdentifier fingerprint = identify(
                providerType,
                providerType.name() + "_QUERY_PAYMENT_FACT",
                canonicalize(providerType, canonicalFact));
        HmacIdentifier providerTradeFingerprint = identify(
                providerType,
                providerType.name() + "_PROVIDER_TRADE",
                providerPid(providerType) + "\n"
                        + PaymentProviderReference.rawTradeNo(fact.providerTradeNo()));
        PaymentCallbackSnapshot snapshot = new PaymentCallbackSnapshot(
                PaymentCallbackSnapshot.CURRENT_SCHEMA_VERSION,
                base64UrlCodec.encode(idWorker.nextId()),
                localOrder.orderId(),
                providerPid(providerType),
                fact.providerTradeNo(),
                fact.channelTradeNo() == null
                        ? PaymentProviderReference.rawTradeNo(fact.providerTradeNo())
                        : fact.channelTradeNo(),
                localOrder.payType(),
                SUCCESS,
                fact.amountYuan(),
                fact.finishedAt(),
                receivedAt,
                receivedAt.toEpochSecond(),
                fingerprint.value(),
                payloadDigest(providerType, canonicalFact));
        PaymentCallbackEnqueueResult result = callbackQueue.enqueue(
                snapshot, fingerprint, providerTradeFingerprint);
        return result.enqueued() || result.callbackId() != null;
    }

    private String providerPid(PaymentProviderType providerType) {
        return providerType == PaymentProviderType.BAR
                ? properties.bar().pid()
                : properties.liuhao().pid();
    }

    private String canonicalize(
            PaymentProviderType providerType,
            Map<String, Object> fields) {
        if (providerType == PaymentProviderType.BAR) {
            return requireBarSignatures().canonicalize(fields);
        }
        return requireLiuhaoSignatures().canonicalize(fields);
    }

    private HmacIdentifier identify(
            PaymentProviderType providerType,
            String purpose,
            String canonicalValue) {
        if (providerType == PaymentProviderType.BAR) {
            return requireBarSignatures().identify(
                    properties.bar().activeKeyVersion(), purpose, canonicalValue);
        }
        return requireLiuhaoSignatures().identify(purpose, canonicalValue);
    }

    private String payloadDigest(
            PaymentProviderType providerType,
            Map<String, Object> fields) {
        return providerType == PaymentProviderType.BAR
                ? requireBarSignatures().payloadDigest(fields)
                : requireLiuhaoSignatures().payloadDigest(fields);
    }

    private BarPaymentSignatureService requireBarSignatures() {
        BarPaymentSignatureService signatures = barSignatures.getIfAvailable();
        if (signatures == null) {
            throw new IllegalStateException("BAR payment signature service is unavailable.");
        }
        return signatures;
    }

    private LiuhaoPaymentSignatureService requireLiuhaoSignatures() {
        LiuhaoPaymentSignatureService signatures = liuhaoSignatures.getIfAvailable();
        if (signatures == null) {
            throw new IllegalStateException("Liuhao payment signature service is unavailable.");
        }
        return signatures;
    }
}
