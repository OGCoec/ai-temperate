package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.mapper.ai.AiConversationGenerationPayloadMapper;
import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.service.user.aiconversation.billing.AiConversationSettlementService;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingCommand;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingMode;
import com.example.temperate.service.user.aiconversation.generation.billing.impl.AiConversationGenerationBillingTransactionServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * 验证资金结算成功后才推进 Generation，结算异常时不会写入伪终态。
 */
class AiConversationGenerationBillingTransactionServiceTest {

    @Test
    void fullRefundUpdatesGenerationOnlyAfterSettlementReturns() {
        Fixture fixture = fixture();
        when(fixture.mapper.completeBilling(
                any(), anyInt(), anyInt(), anyInt(), any(OffsetDateTime.class)))
                .thenReturn(1);

        var result = fixture.service.settle(refundCommand());

        assertThat(result.applied()).isTrue();
        assertThat(result.finalStatus()).isEqualTo("REFUNDED");
        var order = inOrder(fixture.settlement, fixture.mapper);
        order.verify(fixture.settlement).refundFailed(
                eq(fixture.generation.getUsageId()),
                eq("UPSTREAM_FAILED"),
                eq("AI_UPSTREAM_STREAM_FAILED"));
        order.verify(fixture.mapper).completeBilling(
                any(), eq(1),
                eq(AiConversationGenerationStatus.TERMINAL_PENDING_BILLING.code()),
                eq(AiConversationGenerationStatus.REFUNDED.code()),
                any(OffsetDateTime.class));
    }

    @Test
    void settlementFailureDoesNotAdvanceGenerationStatus() {
        Fixture fixture = fixture();
        org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable"))
                .when(fixture.settlement)
                .refundFailed(any(), any(), any());

        assertThatThrownBy(() -> fixture.service.settle(refundCommand()))
                .isInstanceOf(IllegalStateException.class);
        verify(fixture.mapper, never()).completeBilling(
                any(), anyInt(), anyInt(), anyInt(), any());
    }

    private static AiConversationGenerationBillingCommand refundCommand() {
        return new AiConversationGenerationBillingCommand(
                bytes(1),
                1,
                AiConversationGenerationBillingMode.REFUND_FULL,
                null,
                "UPSTREAM_FAILED",
                "AI_UPSTREAM_STREAM_FAILED");
    }

    private static Fixture fixture() {
        AiConversationGenerationMapper mapper = mock(AiConversationGenerationMapper.class);
        AiConversationGenerationPayloadMapper payloadMapper =
                mock(AiConversationGenerationPayloadMapper.class);
        AiConversationSettlementService settlement = mock(AiConversationSettlementService.class);
        AiConversationGeneration generation = new AiConversationGeneration();
        generation.setId(bytes(1));
        generation.setUsageId(bytes(2));
        generation.setGenerationStatus(
                AiConversationGenerationStatus.TERMINAL_PENDING_BILLING.code());
        generation.setTerminalVersion(1);
        when(mapper.findByIdForUpdate(any())).thenReturn(generation);
        var service = new AiConversationGenerationBillingTransactionServiceImpl(
                mapper,
                payloadMapper,
                settlement,
                Clock.fixed(Instant.parse("2026-08-01T16:00:00Z"), ZoneOffset.UTC));
        return new Fixture(mapper, settlement, generation, service);
    }

    private static byte[] bytes(int marker) {
        byte[] value = new byte[16];
        value[15] = (byte) marker;
        return value;
    }

    private record Fixture(
            AiConversationGenerationMapper mapper,
            AiConversationSettlementService settlement,
            AiConversationGeneration generation,
            AiConversationGenerationBillingTransactionServiceImpl service) {
    }
}
