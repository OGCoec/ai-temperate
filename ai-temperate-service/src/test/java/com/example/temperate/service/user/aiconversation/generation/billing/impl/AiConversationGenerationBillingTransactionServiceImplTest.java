package com.example.temperate.service.user.aiconversation.generation.billing.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.mapper.ai.AiConversationGenerationPayloadMapper;
import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.service.user.aiconversation.billing.AiConversationSettlementCommand;
import com.example.temperate.service.user.aiconversation.billing.AiConversationSettlementResult;
import com.example.temperate.service.user.aiconversation.billing.AiConversationSettlementService;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleTraceContext;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationStatus;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingCommand;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证图片成本证据缺失时，资金事务保存成功输出并把 Generation 与 usage 一起置为待对账。
 */
final class AiConversationGenerationBillingTransactionServiceImplTest {

    @Test
    void completeReconcileKeepsSuccessfulMessageAndMarksGeneration() {
        byte[] generationId = new byte[] {1};
        byte[] usageId = new byte[] {2};
        AiConversationGeneration generation = new AiConversationGeneration();
        generation.setId(generationId);
        generation.setUsageId(usageId);
        generation.setTerminalVersion(4);
        generation.setGenerationStatus(
                AiConversationGenerationStatus.TERMINAL_PENDING_BILLING.code());
        AiConversationGenerationMapper generationMapper =
                mock(AiConversationGenerationMapper.class);
        AiConversationGenerationPayloadMapper payloadMapper =
                mock(AiConversationGenerationPayloadMapper.class);
        AiConversationSettlementService settlementService =
                mock(AiConversationSettlementService.class);
        AiConversationSettlementCommand settlementCommand =
                new AiConversationSettlementCommand(
                        usageId,
                        99L,
                        new AiConversationContent("prompt", List.of()),
                        new AiConversationContent("", List.of()),
                        List.of(),
                        null,
                        "safe-request",
                        "STOP",
                        AiConversationLifecycleTraceContext.unavailable());
        when(generationMapper.findByIdForUpdate(generationId))
                .thenReturn(generation);
        when(settlementService.completeReconcile(
                        settlementCommand,
                        "AI_IMAGE_COST_EVIDENCE_MISSING"))
                .thenReturn(new AiConversationSettlementResult(
                        99L, 100L, 0L, true));
        when(generationMapper.completeBilling(
                        eq(generationId),
                        eq(4),
                        eq(AiConversationGenerationStatus
                                .TERMINAL_PENDING_BILLING.code()),
                        eq(AiConversationGenerationStatus
                                .RECONCILE_REQUIRED.code()),
                        eq("AI_IMAGE_COST_EVIDENCE_MISSING"),
                        any(OffsetDateTime.class)))
                .thenReturn(1);
        AiConversationGenerationBillingTransactionServiceImpl service =
                new AiConversationGenerationBillingTransactionServiceImpl(
                        generationMapper,
                        payloadMapper,
                        settlementService,
                        Clock.fixed(
                                Instant.parse("2026-08-05T12:00:00Z"),
                                ZoneOffset.UTC));

        var result = service.settle(new AiConversationGenerationBillingCommand(
                generationId,
                4,
                AiConversationGenerationBillingMode.COMPLETE_RECONCILE,
                settlementCommand,
                "COMPLETED",
                "AI_IMAGE_COST_EVIDENCE_MISSING"));

        assertThat(result.finalStatus()).isEqualTo("RECONCILE_REQUIRED");
        assertThat(result.messageId()).isEqualTo(99L);
        verify(settlementService).completeReconcile(
                settlementCommand, "AI_IMAGE_COST_EVIDENCE_MISSING");
    }
}
