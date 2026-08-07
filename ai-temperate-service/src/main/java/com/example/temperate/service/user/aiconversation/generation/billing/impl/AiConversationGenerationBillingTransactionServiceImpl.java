package com.example.temperate.service.user.aiconversation.generation.billing.impl;

import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.mapper.ai.AiConversationGenerationPayloadMapper;
import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.model.ai.entity.AiConversationGenerationPayload;
import com.example.temperate.service.user.aiconversation.billing.AiConversationSettlementResult;
import com.example.temperate.service.user.aiconversation.billing.AiConversationSettlementService;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationStatus;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingCommand;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingMode;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingResult;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingTransactionService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 锁定 Generation 并复用既有 Settlement 权威算法，在一个本地事务中提交资金和 Generation 终态。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class AiConversationGenerationBillingTransactionServiceImpl
        implements AiConversationGenerationBillingTransactionService {

    private final AiConversationGenerationMapper generationMapper;
    private final AiConversationSettlementService settlementService;
    private final AiConversationGenerationPayloadMapper payloadMapper;
    private final Clock clock;

    public AiConversationGenerationBillingTransactionServiceImpl(
            AiConversationGenerationMapper generationMapper,
            AiConversationGenerationPayloadMapper payloadMapper,
            AiConversationSettlementService settlementService,
            Clock clock) {
        this.generationMapper = Objects.requireNonNull(generationMapper);
        this.payloadMapper = Objects.requireNonNull(payloadMapper);
        this.settlementService = Objects.requireNonNull(settlementService);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public long getOrReserveMessageId(byte[] generationId) {
        AiConversationGenerationPayload payload = payloadMapper
                .findByGenerationIdForUpdate(generationId);
        if (payload == null) {
            throw new IllegalStateException("AI Generation payload is missing during message reservation.");
        }
        if (payload.getConversationMessageId() != null) {
            return payload.getConversationMessageId();
        }
        long messageId = settlementService.reserveMessageId();
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        if (payloadMapper.assignConversationMessageId(generationId, messageId, now) != 1) {
            throw new IllegalStateException("AI Generation message reservation did not affect one row.");
        }
        return messageId;
    }

    @Override
    @Transactional
    public AiConversationGenerationBillingResult settle(
            AiConversationGenerationBillingCommand command) {
        AiConversationGeneration generation = generationMapper.findByIdForUpdate(
                command.generationId());
        if (generation == null) {
            throw new IllegalStateException("AI Generation is missing during billing.");
        }
        if (generation.getGenerationStatus()
                != AiConversationGenerationStatus.TERMINAL_PENDING_BILLING.code()) {
            return new AiConversationGenerationBillingResult(
                    false,
                    status(generation.getGenerationStatus()).name(),
                    0L);
        }
        if (generation.getTerminalVersion() != command.terminalVersion()) {
            throw new IllegalStateException("AI Generation terminal version does not match.");
        }

        AiConversationSettlementResult settlementResult = null;
        int finalStatus;
        if (command.mode() == AiConversationGenerationBillingMode.REFUND_FULL) {
            settlementService.refundFailed(
                    generation.getUsageId(),
                    command.finishReason(),
                    command.failureCode());
            finalStatus = AiConversationGenerationStatus.REFUNDED.code();
        } else if (command.mode() == AiConversationGenerationBillingMode.COMPLETE) {
            settlementResult = settlementService.complete(command.settlementCommand());
            finalStatus = settlementResult.requiresReconciliation()
                    ? AiConversationGenerationStatus.RECONCILE_REQUIRED.code()
                    : AiConversationGenerationStatus.SETTLED.code();
        } else if (command.mode()
                == AiConversationGenerationBillingMode.COMPLETE_RECONCILE) {
            settlementResult = settlementService.completeReconcile(
                    command.settlementCommand(), command.failureCode());
            finalStatus = AiConversationGenerationStatus.RECONCILE_REQUIRED.code();
        } else {
            settlementResult = settlementService.settleInterrupted(
                    command.settlementCommand());
            finalStatus = settlementResult.requiresReconciliation()
                    ? AiConversationGenerationStatus.RECONCILE_REQUIRED.code()
                    : AiConversationGenerationStatus.SETTLED.code();
        }
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        if (generationMapper.completeBilling(
                command.generationId(),
                command.terminalVersion(),
                AiConversationGenerationStatus.TERMINAL_PENDING_BILLING.code(),
                finalStatus,
                command.mode()
                                == AiConversationGenerationBillingMode.COMPLETE_RECONCILE
                        ? command.failureCode()
                        : null,
                now) != 1) {
            throw new IllegalStateException("AI Generation billing CAS did not affect one row.");
        }
        return new AiConversationGenerationBillingResult(
                true,
                status(finalStatus).name(),
                settlementResult == null ? 0L : settlementResult.messageId());
    }

    private static AiConversationGenerationStatus status(int code) {
        for (AiConversationGenerationStatus value : AiConversationGenerationStatus.values()) {
            if (value.code() == code) {
                return value;
            }
        }
        throw new IllegalStateException("Unknown AI Generation status.");
    }
}
