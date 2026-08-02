package com.example.temperate.service.user.aiconversation.generation.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationDispatchEvent;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalCommand;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalEvent;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalService;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalType;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationEventPublisher;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 在本地事务提交后发布 Generation 与 Terminal；初始发布失败且模型未调用时冻结系统失败事实供全额退款。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class AiConversationGenerationAfterCommitPublisherImpl {

    private final AiConversationGenerationEventPublisher publisher;
    private final AiConversationGenerationTerminalService terminalService;
    private final HybridBase64UrlCodec idCodec;

    public AiConversationGenerationAfterCommitPublisherImpl(
            AiConversationGenerationEventPublisher publisher,
            AiConversationGenerationTerminalService terminalService,
            HybridBase64UrlCodec idCodec) {
        this.publisher = Objects.requireNonNull(publisher);
        this.terminalService = Objects.requireNonNull(terminalService);
        this.idCodec = Objects.requireNonNull(idCodec);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishDispatch(AiConversationGenerationDispatchEvent event) {
        try {
            publisher.publishGenerationRequested(
                    event.generationPublicId(), event.usagePublicId(), event.traceId());
        } catch (RuntimeException failure) {
            terminalService.freeze(new AiConversationGenerationTerminalCommand(
                    idCodec.decode(event.generationPublicId()),
                    AiConversationGenerationTerminalType.SYSTEM_FAILED,
                    "AI_GENERATION_DISPATCH_FAILED",
                    "",
                    "[]",
                    null,
                    "SYSTEM_FAILED",
                    null,
                    event.traceId()));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishTerminal(AiConversationGenerationTerminalEvent event) {
        publisher.publishTerminated(
                event.generationPublicId(),
                event.usagePublicId(),
                event.terminalType(),
                event.terminalReason(),
                event.terminalVersion(),
                event.traceId());
    }
}
