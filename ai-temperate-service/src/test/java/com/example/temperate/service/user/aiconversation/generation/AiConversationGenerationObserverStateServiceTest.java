package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationDetachedEvent;
import com.example.temperate.service.user.aiconversation.generation.observer.impl.AiConversationGenerationObserverStateServiceImpl;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 验证 Observer 结束只提交 epoch 保护的 DETACHED 事实，并统一 PostgreSQL 与 Rabbit 时间精度。
 */
class AiConversationGenerationObserverStateServiceTest {

    @Test
    void successfulDetachPublishesMicrosecondNormalizedCheckFact() {
        byte[] generationId = new byte[16];
        generationId[15] = 7;
        AiConversationGenerationMapper mapper = mock(AiConversationGenerationMapper.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-01T16:00:00.123456789Z"), ZoneOffset.UTC);
        when(mapper.detachObserver(
                eq(generationId), eq(42L), eq(5L), eq(0), eq(1), any()))
                .thenReturn(1);
        var service = new AiConversationGenerationObserverStateServiceImpl(
                mapper,
                events,
                new AiConversationMetrics(new SimpleMeterRegistry()),
                clock);

        service.detach(42L, generationId, "generation-safe", 5L, "trace-safe");

        ArgumentCaptor<AiConversationGenerationDetachedEvent> event =
                ArgumentCaptor.forClass(AiConversationGenerationDetachedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().observerEpoch()).isEqualTo(5L);
        assertThat(event.getValue().detachedAt().getNano()).isEqualTo(123_456_000);
    }

    @Test
    void staleObserverDoesNotPublishDetachCheck() {
        byte[] generationId = new byte[16];
        AiConversationGenerationMapper mapper = mock(AiConversationGenerationMapper.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        var service = new AiConversationGenerationObserverStateServiceImpl(
                mapper,
                events,
                new AiConversationMetrics(new SimpleMeterRegistry()),
                Clock.fixed(Instant.parse("2026-08-01T16:00:00Z"), ZoneOffset.UTC));

        service.detach(42L, generationId, "generation-safe", 4L, "trace-safe");

        verify(events, never()).publishEvent(any());
    }
}
