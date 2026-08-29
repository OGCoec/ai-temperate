package com.example.temperate.service.user.membership.payment.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.mapper.user.membership.payment.MembershipPaymentCallbackMapper;
import com.example.temperate.model.user.membership.payment.MembershipPaymentCallbackWriteResult;
import com.example.temperate.model.user.membership.payment.MembershipPaymentRefundTerminalFact;
import com.example.temperate.service.user.membership.payment.callback.impl.PaymentCallbackPersistenceServiceImpl;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 该持久化测试是来约束 Mapper 批量结果必须按唯一 ordinal 精确对应原回调 ID、订单 ID和业务键。
 */
final class PaymentCallbackPersistenceServiceImplTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 20, 12, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void duplicateMapperOrdinalIsRejectedEvenWhenResultCountMatches() {
        HybridBase64UrlCodec codec = new HybridBase64UrlCodec();
        MembershipPaymentCallbackMapper mapper = mock(MembershipPaymentCallbackMapper.class);
        PaymentCallbackSnapshot first = callback(codec, (byte) 1, (byte) 11, "trade-1");
        PaymentCallbackSnapshot second = callback(codec, (byte) 2, (byte) 12, "trade-2");
        MembershipPaymentCallbackWriteResult repeated = result(codec, first, 1);
        when(mapper.batchInsertOrResolve(anyString())).thenReturn(List.of(repeated, repeated));
        PaymentCallbackPersistenceService service =
                new PaymentCallbackPersistenceServiceImpl(
                        mapper, codec, new ObjectMapper().findAndRegisterModules());

        assertThatThrownBy(() -> service.persist(List.of(first, second)))
                .isInstanceOf(MembershipPaymentInfrastructureException.class)
                .hasMessageContaining("ordinal");
    }

    @Test
    void refundTerminalFactsAreLoadedInOneBatchAndKeyedByCanonicalCallbackId() {
        HybridBase64UrlCodec codec = new HybridBase64UrlCodec();
        MembershipPaymentCallbackMapper mapper = mock(MembershipPaymentCallbackMapper.class);
        String callbackId = codec.encode(bytes((byte) 31));
        MembershipPaymentRefundTerminalFact fact = new MembershipPaymentRefundTerminalFact();
        fact.setCallbackId(codec.decode(callbackId));
        fact.setOrderId(bytes((byte) 32));
        when(mapper.findRefundTerminalFactsByIdsJson(anyString())).thenReturn(List.of(fact));
        PaymentCallbackPersistenceService service = new PaymentCallbackPersistenceServiceImpl(
                mapper, codec, new ObjectMapper().findAndRegisterModules());

        Map<String, MembershipPaymentRefundTerminalFact> facts =
                service.findRefundTerminalFacts(List.of(callbackId));

        assertThat(facts).containsOnlyKeys(callbackId);
        assertThat(facts.get(callbackId)).isSameAs(fact);
    }

    private static PaymentCallbackSnapshot callback(
            HybridBase64UrlCodec codec,
            byte callbackByte,
            byte orderByte,
            String tradeNo) {
        return new PaymentCallbackSnapshot(
                PaymentCallbackSnapshot.CURRENT_SCHEMA_VERSION,
                codec.encode(bytes(callbackByte)),
                codec.encode(bytes(orderByte)),
                "merchant-test",
                tradeNo,
                "channel-" + tradeNo,
                "alipay",
                "TRADE_SUCCESS",
                new BigDecimal("20.00"),
                NOW,
                NOW,
                NOW.toEpochSecond(),
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
    }

    private static MembershipPaymentCallbackWriteResult result(
            HybridBase64UrlCodec codec,
            PaymentCallbackSnapshot callback,
            int ordinal) {
        MembershipPaymentCallbackWriteResult result =
                new MembershipPaymentCallbackWriteResult();
        result.setOrdinal(ordinal);
        result.setCallbackId(codec.decode(callback.callbackId()));
        result.setRequestedOrderId(codec.decode(callback.orderId()));
        result.setPersistedOrderId(codec.decode(callback.orderId()));
        result.setPersistedCallbackId(codec.decode(callback.callbackId()));
        result.setProviderTradeNo(callback.providerTradeNo());
        result.setTradeStatus(callback.tradeStatus());
        result.setInserted(true);
        result.setDuplicate(false);
        result.setSameCallback(true);
        result.setOrderMismatch(false);
        return result;
    }

    private static byte[] bytes(byte value) {
        byte[] result = new byte[16];
        Arrays.fill(result, value);
        return result;
    }
}
