package com.example.temperate.service.user.aiconversation.generation.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.mapper.ai.AiConversationGenerationPayloadMapper;
import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationStatus;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalCommand;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalEvent;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalResult;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalService;
import com.example.temperate.service.user.aiconversation.model.AiConversationUsage;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在新事务中锁定 Generation，通过终态版本 CAS 与 Payload 一次性写入冻结唯一事实证据。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class AiConversationGenerationTerminalServiceImpl
        implements AiConversationGenerationTerminalService {

    private static final List<Integer> FREEZABLE_STATUSES = List.of(
            AiConversationGenerationStatus.QUEUED.code(),
            AiConversationGenerationStatus.RUNNING.code(),
            AiConversationGenerationStatus.CANCEL_REQUESTED.code());

    private final AiConversationGenerationMapper generationMapper;
    private final AiConversationGenerationPayloadMapper payloadMapper;
    private final HybridBase64UrlCodec idCodec;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public AiConversationGenerationTerminalServiceImpl(
            AiConversationGenerationMapper generationMapper,
            AiConversationGenerationPayloadMapper payloadMapper,
            HybridBase64UrlCodec idCodec,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.generationMapper = Objects.requireNonNull(generationMapper);
        this.payloadMapper = Objects.requireNonNull(payloadMapper);
        this.idCodec = Objects.requireNonNull(idCodec);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AiConversationGenerationTerminalResult freeze(
            AiConversationGenerationTerminalCommand command) {
        AiConversationGeneration generation = generationMapper.findByIdForUpdate(
                command.generationId());
        if (generation == null) {
            throw new IllegalStateException("AI Generation is missing.");
        }
        if (generation.getTerminalType() != null) {
            return result(false, generation);
        }
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        AiConversationUsage usage = command.usage();
        if (payloadMapper.freezeTerminalEvidence(
                command.generationId(),
                command.assistantText(),
                command.assistantAttachmentsJson(),
                usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.completionTokens(),
                usage == null ? null : usage.cachedPromptTokens(),
                usage == null ? null : usage.reasoningTokens(),
                command.modelFinishReason(),
                command.upstreamRequestId(),
                now) != 1) {
            throw new IllegalStateException("AI Generation terminal evidence was already frozen.");
        }
        if (generationMapper.freezeTerminal(
                command.generationId(),
                FREEZABLE_STATUSES,
                generation.getTerminalVersion(),
                AiConversationGenerationStatus.TERMINAL_PENDING_BILLING.code(),
                command.terminalType().name(),
                command.terminalReason(),
                now) != 1) {
            throw new IllegalStateException("AI Generation terminal CAS did not affect one row.");
        }
        generation.setTerminalType(command.terminalType().name());
        generation.setTerminalReason(command.terminalReason());
        generation.setTerminalVersion(generation.getTerminalVersion() + 1);
        AiConversationGenerationTerminalResult result = result(true, generation);
        eventPublisher.publishEvent(new AiConversationGenerationTerminalEvent(
                result.generationPublicId(),
                result.usagePublicId(),
                result.terminalType(),
                result.terminalReason(),
                result.terminalVersion(),
                command.traceId()));
        return result;
    }

    private AiConversationGenerationTerminalResult result(
            boolean claimed,
            AiConversationGeneration generation) {
        return new AiConversationGenerationTerminalResult(
                claimed,
                idCodec.encode(generation.getId()),
                idCodec.encode(generation.getUsageId()),
                generation.getTerminalType(),
                generation.getTerminalReason(),
                generation.getTerminalVersion());
    }
}
