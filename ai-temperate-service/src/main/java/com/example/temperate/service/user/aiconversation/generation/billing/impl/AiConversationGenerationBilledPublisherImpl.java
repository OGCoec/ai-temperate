package com.example.temperate.service.user.aiconversation.generation.billing.impl;

import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBilledEvent;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationOutputStore;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 在资金与 Generation 终态提交后写入 Redis 展示终态；失败向 Rabbit Consumer 传播以触发有限补发，但不会回滚 PostgreSQL。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class AiConversationGenerationBilledPublisherImpl {

    private final AiConversationGenerationOutputStore outputStore;

    public AiConversationGenerationBilledPublisherImpl(
            AiConversationGenerationOutputStore outputStore) {
        this.outputStore = Objects.requireNonNull(outputStore);
    }

    @EventListener
    public void publish(AiConversationGenerationBilledEvent event) {
        outputStore.publishTerminal(
                event.generationPublicId(), event.eventName(), event.dataJson());
    }
}
