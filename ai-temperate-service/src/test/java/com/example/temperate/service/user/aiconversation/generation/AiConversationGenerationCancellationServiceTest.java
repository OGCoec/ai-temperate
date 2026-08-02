package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.service.user.aiconversation.generation.cancellation.AiConversationGenerationCancellationEvent;
import com.example.temperate.service.user.aiconversation.generation.cancellation.impl.AiConversationGenerationCancellationServiceImpl;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 验证显式 Stop 与失联检查只通过 Generation CAS 请求取消，旧 observer epoch 不产生取消事件。
 */
class AiConversationGenerationCancellationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T16:00:00Z");

    @Test
    void staleDetachEpochIsIgnoredWithoutChangingGenerationState() {
        Fixture fixture = fixture();
        AiConversationGeneration generation = generation();
        generation.setObserverStatus(AiConversationGenerationObserverStatus.DETACHED.code());
        generation.setObserverEpoch(9L);
        generation.setDetachedAt(NOW.minusSeconds(30).atOffset(ZoneOffset.UTC));
        when(fixture.mapper.findByIdForUpdate(generation.getId())).thenReturn(generation);

        var result = fixture.service.requestDetachedTimeout(
                generation.getId(),
                8L,
                generation.getDetachedAt(),
                "trace-safe");

        assertThat(result.status()).isEqualTo("STALE_DETACH_CHECK");
        verify(fixture.mapper, never()).requestCancellation(
                any(), anyList(), anyInt(), any(), any());
        verify(fixture.events, never()).publishEvent(any(Object.class));
    }

    @Test
    void firstUserStopPersistsTrustedSourceAndPublishesOneControlFact() {
        Fixture fixture = fixture();
        AiConversationGeneration generation = generation();
        generation.setOwnerInstanceId("instance-a");
        when(fixture.mapper.findByIdForUpdate(generation.getId())).thenReturn(generation);
        when(fixture.mapper.requestCancellation(
                any(), anyList(), anyInt(), any(), any())).thenReturn(1);

        var result = fixture.service.requestUserStop(42L, generation.getId());

        assertThat(result.status()).isEqualTo("CANCEL_REQUESTED");
        assertThat(result.cancelSource()).isEqualTo("USER_STOP");
        verify(fixture.mapper).requestCancellation(
                eq(generation.getId()),
                anyList(),
                eq(AiConversationGenerationStatus.CANCEL_REQUESTED.code()),
                eq("USER_STOP"),
                any(OffsetDateTime.class));
        verify(fixture.events).publishEvent(any(AiConversationGenerationCancellationEvent.class));
    }

    @Test
    void detachTimestampWithDifferentUtcOffsetStillMatchesTheSameInstant() {
        Fixture fixture = fixture();
        AiConversationGeneration generation = generation();
        generation.setObserverStatus(AiConversationGenerationObserverStatus.DETACHED.code());
        generation.setObserverEpoch(3L);
        generation.setDetachedAt(OffsetDateTime.parse("2026-08-01T11:00:00-05:00"));
        when(fixture.mapper.findByIdForUpdate(generation.getId())).thenReturn(generation);
        when(fixture.mapper.requestCancellation(
                any(), anyList(), anyInt(), any(), any())).thenReturn(1);

        var result = fixture.service.requestDetachedTimeout(
                generation.getId(),
                3L,
                OffsetDateTime.parse("2026-08-01T16:00:00Z"),
                "trace-safe");

        assertThat(result.status()).isEqualTo("CANCEL_REQUESTED");
        assertThat(result.cancelSource()).isEqualTo("CLIENT_EXIT_TIMEOUT");
    }

    private static Fixture fixture() {
        AiConversationGenerationMapper mapper = mock(AiConversationGenerationMapper.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        AiConversationGenerationCancellationServiceImpl service =
                new AiConversationGenerationCancellationServiceImpl(
                        mapper,
                        new HybridBase64UrlCodec(),
                        events,
                        new AiConversationMetrics(new SimpleMeterRegistry()),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(mapper, events, service);
    }

    private static AiConversationGeneration generation() {
        byte[] id = new byte[16];
        id[15] = 1;
        AiConversationGeneration generation = new AiConversationGeneration();
        generation.setId(id);
        generation.setLoginIdentityId(42L);
        generation.setGenerationStatus(AiConversationGenerationStatus.RUNNING.code());
        generation.setObserverStatus(AiConversationGenerationObserverStatus.ATTACHED.code());
        generation.setObserverEpoch(1L);
        generation.setTerminalVersion(0);
        return generation;
    }

    private record Fixture(
            AiConversationGenerationMapper mapper,
            ApplicationEventPublisher events,
            AiConversationGenerationCancellationServiceImpl service) {
    }
}
