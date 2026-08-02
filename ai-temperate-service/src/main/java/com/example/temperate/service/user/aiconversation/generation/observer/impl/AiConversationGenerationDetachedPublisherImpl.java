package com.example.temperate.service.user.aiconversation.generation.observer.impl;

import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationDetachedEvent;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationEventPublisher;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 在 DETACHED 事务提交后发布固定宽限期检查，发布失败只影响自动退出检测并由现有恢复任务兜底。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class AiConversationGenerationDetachedPublisherImpl {

    private final AiConversationGenerationEventPublisher publisher;

    public AiConversationGenerationDetachedPublisherImpl(
            AiConversationGenerationEventPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(AiConversationGenerationDetachedEvent event) {
        try {
            publisher.publishDetachCheck(
                    event.generationPublicId(),
                    event.observerEpoch(),
                    event.detachedAt(),
                    event.traceId());
        } catch (RuntimeException ignoredFailure) {
            // 取消意图尚未产生，延迟消息失败不能让已经提交的请求伪装成创建失败；分钟级恢复会检查 DETACHED 行。
        }
    }
}
