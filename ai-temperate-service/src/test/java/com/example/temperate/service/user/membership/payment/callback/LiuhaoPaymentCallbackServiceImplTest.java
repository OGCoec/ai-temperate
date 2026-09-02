package com.example.temperate.service.user.membership.payment.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.callback.impl.LiuhaoPaymentCallbackServiceImpl;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptTransactionService;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentOrderLookupService;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProviderRegistry;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderReference;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.provider.liuhao.LiuhaoPaymentSignatureService;
import com.example.temperate.service.user.membership.payment.provider.liuhao.LiuhaoSignatureVerificationReason;
import com.example.temperate.service.user.membership.payment.provider.liuhao.LiuhaoSignatureVerificationResult;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.MembershipProviderTradeNoPatchOutcome;
import com.example.temperate.service.user.membership.payment.store.PaymentCallbackQueue;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来约束六号可信回调在入队前同步完成 PostgreSQL 与 Redis 的 ORDER 到 TRADE 幂等升级。
 */
final class LiuhaoPaymentCallbackServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-31T09:00:00Z");
    private static final byte[] ORDER_BYTES = id((byte) 11);
    private static final String ORDER_ID = new HybridBase64UrlCodec().encode(ORDER_BYTES);
    private static final String RAW_TRADE_NO = "2026083109000012345";
    private static final String TRADE_REFERENCE =
            PaymentProviderReference.trade(PaymentProviderType.LIUHAO, RAW_TRADE_NO);

    @Test
    void verifiedCallbackBindsDatabaseAndRedisBeforeQueueing() {
        MembershipPaymentOrderLookupService lookup =
                mock(MembershipPaymentOrderLookupService.class);
        MembershipPaymentAttemptTransactionService transaction =
                mock(MembershipPaymentAttemptTransactionService.class);
        MembershipPaymentProviderRegistry registry =
                mock(MembershipPaymentProviderRegistry.class);
        MembershipOrderSnapshotStore orderStore = mock(MembershipOrderSnapshotStore.class);
        PaymentCallbackQueue callbackQueue = mock(PaymentCallbackQueue.class);
        LiuhaoPaymentSignatureService signatures = mock(LiuhaoPaymentSignatureService.class);
        HybridSemaphoreIdWorker idWorker = mock(HybridSemaphoreIdWorker.class);
        MembershipPaymentProvider provider = mock(MembershipPaymentProvider.class);
        MembershipOrderSnapshot order = order();
        when(lookup.find(ORDER_ID)).thenReturn(Optional.of(order));
        when(registry.getRequired(PaymentProviderType.LIUHAO)).thenReturn(provider);
        when(provider.queryPayment(any())).thenReturn(new PaymentQueryResult(
                ORDER_ID,
                TRADE_REFERENCE,
                RAW_TRADE_NO,
                PaymentProviderStatus.PAID,
                new BigDecimal("0.05"),
                NOW.atOffset(ZoneOffset.UTC),
                null));
        when(signatures.verifyDetailed(any())).thenReturn(
                LiuhaoSignatureVerificationResult.success());
        when(signatures.canonicalize(any())).thenReturn("canonical");
        when(signatures.identify(anyString(), anyString())).thenReturn(
                HmacIdentifier.fromProtectedValue("a".repeat(43)));
        when(signatures.payloadDigest(any())).thenReturn("b".repeat(43));
        when(idWorker.nextId()).thenReturn(id((byte) 12));
        when(orderStore.patchProviderTradeNo(ORDER_ID, 17L, TRADE_REFERENCE))
                .thenReturn(MembershipProviderTradeNoPatchOutcome.APPLIED);
        when(callbackQueue.enqueue(any(), any(), any())).thenReturn(
                new PaymentCallbackEnqueueResult(
                        PaymentCallbackEnqueueOutcome.ENQUEUED,
                        new HybridBase64UrlCodec().encode(id((byte) 12))));
        LiuhaoPaymentCallbackService service = new LiuhaoPaymentCallbackServiceImpl(
                lookup,
                transaction,
                registry,
                orderStore,
                callbackQueue,
                signatures,
                idWorker,
                new HybridBase64UrlCodec(),
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(MembershipPaymentMetrics.class));

        boolean duplicate = service.receive(command());

        assertThat(duplicate).isFalse();
        verify(signatures).verifyDetailed(command().externalFields());
        verify(transaction).bindProviderTradeNo(17L, ORDER_BYTES, TRADE_REFERENCE);
        verify(orderStore).patchProviderTradeNo(ORDER_ID, 17L, TRADE_REFERENCE);
        verify(callbackQueue).enqueue(any(), any(), any());
    }

    @Test
    void invalidSignatureStopsBeforeOrderLookupProviderQueryAndQueueing() {
        MembershipPaymentOrderLookupService lookup =
                mock(MembershipPaymentOrderLookupService.class);
        MembershipPaymentAttemptTransactionService transaction =
                mock(MembershipPaymentAttemptTransactionService.class);
        MembershipPaymentProviderRegistry registry =
                mock(MembershipPaymentProviderRegistry.class);
        MembershipOrderSnapshotStore orderStore = mock(MembershipOrderSnapshotStore.class);
        PaymentCallbackQueue callbackQueue = mock(PaymentCallbackQueue.class);
        LiuhaoPaymentSignatureService signatures = mock(LiuhaoPaymentSignatureService.class);
        MembershipPaymentMetrics metrics = mock(MembershipPaymentMetrics.class);
        when(signatures.verifyDetailed(any())).thenReturn(
                LiuhaoSignatureVerificationResult.failed(
                        LiuhaoSignatureVerificationReason.PLATFORM_SIGNATURE_MISMATCH));
        LiuhaoPaymentCallbackService service = new LiuhaoPaymentCallbackServiceImpl(
                lookup,
                transaction,
                registry,
                orderStore,
                callbackQueue,
                signatures,
                mock(HybridSemaphoreIdWorker.class),
                new HybridBase64UrlCodec(),
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                metrics);

        assertThatThrownBy(() -> service.receive(command()))
                .isInstanceOfSatisfying(
                        MembershipPaymentException.class,
                        exception -> assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.LIUHAO_SIGNATURE_INVALID));

        verify(signatures).verifyDetailed(command().externalFields());
        verify(metrics).callbackRejected();
        verifyNoInteractions(lookup, transaction, registry, orderStore, callbackQueue);
    }

    @Test
    void callbackCommandDefensivelyCopiesTheCompleteExternalFieldSet() {
        Map<String, String> mutable = new LinkedHashMap<>(callbackFields());
        LiuhaoPaymentCallbackCommand command = new LiuhaoPaymentCallbackCommand(mutable);

        mutable.put("future_flag", "changed-after-construction");

        assertThat(command.externalFields().get("future_flag")).isEqualTo("future-value");
        assertThatThrownBy(() -> command.externalFields().put("another_field", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static LiuhaoPaymentCallbackCommand command() {
        return new LiuhaoPaymentCallbackCommand(callbackFields());
    }

    private static Map<String, String> callbackFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("pid", "1001");
        fields.put("trade_no", RAW_TRADE_NO);
        fields.put("out_trade_no", ORDER_ID);
        fields.put("api_trade_no", "channel-trade-1");
        fields.put("type", "alipay");
        fields.put("trade_status", "TRADE_SUCCESS");
        fields.put("addtime", "2026-08-31 08:59:00");
        fields.put("endtime", "2026-08-31 09:00:00");
        fields.put("name", "会员支付订单");
        fields.put("money", "0.05");
        fields.put("param", "");
        fields.put("buyer", "masked-buyer");
        fields.put("timestamp", Long.toString(NOW.getEpochSecond()));
        fields.put("sign_type", "RSA");
        fields.put("sign", "signature-placeholder");
        fields.put("future_flag", "future-value");
        return Map.copyOf(fields);
    }

    private static MembershipOrderSnapshot order() {
        OffsetDateTime now = NOW.atOffset(ZoneOffset.UTC);
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                ORDER_ID,
                17L,
                MembershipTier.GO,
                new BigDecimal("0.05"),
                "alipay",
                MembershipOrderStatus.PENDING_PAYMENT,
                UUID.fromString("4936ef2b-adca-470e-a0ee-d908b45c96db"),
                null,
                now.minusSeconds(5),
                now.plusMinutes(5),
                null,
                null,
                1L,
                now.minusMinutes(1),
                now.minusSeconds(5));
    }

    private static MembershipPaymentProperties properties() {
        MembershipPaymentProperties properties = mock(MembershipPaymentProperties.class);
        when(properties.defaultProvider()).thenReturn(PaymentProviderType.LIUHAO);
        when(properties.liuhao()).thenReturn(new MembershipPaymentProperties.Liuhao(
                true,
                URI.create("https://liuhao.net"),
                "1001",
                "",
                "",
                "",
                URI.create("https://niko000o.site/api/payment/liuhao/notify"),
                URI.create("https://niko000o.site/pages/account/payment-result"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                65_536,
                Duration.ofMinutes(5)));
        return properties;
    }

    private static byte[] id(byte value) {
        byte[] result = new byte[16];
        Arrays.fill(result, value);
        return result;
    }
}
