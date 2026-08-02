package com.example.temperate.service.user.aiconversation.generation.cancellation.impl;

import com.example.temperate.service.user.aiconversation.generation.cancellation.AiConversationGenerationCancellationEvent;
import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationCancelSource;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalCommand;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalService;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalType;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationEventPublisher;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 在取消意图提交后按 owner_instance_id 发布控制命令，消息丢失不覆盖 PostgreSQL 中的取消事实。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class AiConversationGenerationCancellationPublisherImpl {

    private final AiConversationGenerationEventPublisher publisher;
    private final AiConversationGenerationTerminalService terminalService;
    private final HybridBase64UrlCodec idCodec;

    public AiConversationGenerationCancellationPublisherImpl(
            AiConversationGenerationEventPublisher publisher,
            AiConversationGenerationTerminalService terminalService,
            HybridBase64UrlCodec idCodec) {
        this.publisher = Objects.requireNonNull(publisher);
        this.terminalService = Objects.requireNonNull(terminalService);
        this.idCodec = Objects.requireNonNull(idCodec);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(AiConversationGenerationCancellationEvent event) {
        try {
            if (event.ownerInstanceId() == null || event.ownerInstanceId().isBlank()) {
                // 取消事务先于 Worker 领取时没有可路由 Owner，直接冻结无输出取消可避免向 API 实例留下孤儿 pending 标记。
                AiConversationGenerationTerminalType terminalType =
                        AiConversationGenerationCancelSource.ADMIN_CANCEL.name()
                                        .equals(event.cancelSource())
                                ? AiConversationGenerationTerminalType.ADMIN_CANCELLED
                                : AiConversationGenerationTerminalType.CLIENT_CANCELLED;
                terminalService.freeze(new AiConversationGenerationTerminalCommand(
                        idCodec.decode(event.generationPublicId()),
                        terminalType,
                        event.cancelSource(),
                        "",
                        "[]",
                        null,
                        terminalType.name(),
                        null,
                        event.traceId()));
                return;
            }
            publisher.publishCancelRequested(
                    event.generationPublicId(),
                    event.cancelSource(),
                    event.cancelVersion(),
                    event.ownerInstanceId(),
                    event.traceId());
        } catch (RuntimeException ignoredFailure) {
            // PostgreSQL 中的取消事实已经提交，API 仍返回已受理；分钟级恢复负责重发或冻结，而不是伪造事务回滚。
        }
    }
}
