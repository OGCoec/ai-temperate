package com.example.temperate.service.user.aiconversation.generation.observer.impl;

import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationObserverStatus;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationDetachedEvent;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationObserverStateService;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在独立代理事务中应用观察者 epoch CAS，避免 Reactor doFinally 通过同类自调用绕过事务增强。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class AiConversationGenerationObserverStateServiceImpl
        implements AiConversationGenerationObserverStateService {

    private final AiConversationGenerationMapper generationMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final AiConversationMetrics metrics;

    public AiConversationGenerationObserverStateServiceImpl(
            AiConversationGenerationMapper generationMapper,
            ApplicationEventPublisher eventPublisher,
            AiConversationMetrics metrics,
            Clock clock) {
        this.generationMapper = Objects.requireNonNull(generationMapper);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.metrics = Objects.requireNonNull(metrics);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void detach(
            long userId,
            byte[] generationId,
            String generationPublicId,
            long expectedEpoch,
            String traceId) {
        // Rabbit 时间与 PostgreSQL TIMESTAMPTZ 必须使用相同微秒精度，否则同一次 detach 会被精确比较误判为旧消息。
        OffsetDateTime now = clock.instant().truncatedTo(ChronoUnit.MICROS)
                .atOffset(ZoneOffset.UTC);
        if (generationMapper.detachObserver(
                generationId,
                userId,
                expectedEpoch,
                AiConversationGenerationObserverStatus.ATTACHED.code(),
                AiConversationGenerationObserverStatus.DETACHED.code(),
                now) == 1) {
            metrics.observerDetached();
            eventPublisher.publishEvent(new AiConversationGenerationDetachedEvent(
                    generationPublicId, expectedEpoch, now, traceId));
        }
    }
}
