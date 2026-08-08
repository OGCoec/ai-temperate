package com.example.temperate.service.user.aiconversation.generation.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.mapper.ai.AiConversationGenerationPayloadMapper;
import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationStatus;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalCommand;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalEvent;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalType;
import com.example.temperate.service.user.aiconversation.model.AiConversationUsage;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 验证终态行锁内的取消优先级，防止 OSS 完成与用户 Stop 竞态把取消覆盖成成功。
 */
final class AiConversationGenerationTerminalServiceImplTest {

    @Test
    void cancellationRequestedUnderRowLockOverridesCompletedImageEvidence() {
        byte[] generationId = bytes(1);
        byte[] usageId = bytes(2);
        AiConversationGeneration generation = new AiConversationGeneration();
        generation.setId(generationId);
        generation.setUsageId(usageId);
        generation.setGenerationStatus(AiConversationGenerationStatus.CANCEL_REQUESTED.code());
        generation.setCancelSource("USER_STOP");
        generation.setTerminalVersion(0);
        AiConversationGenerationMapper generationMapper =
                mock(AiConversationGenerationMapper.class);
        AiConversationGenerationPayloadMapper payloadMapper =
                mock(AiConversationGenerationPayloadMapper.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        when(generationMapper.findByIdForUpdate(generationId)).thenReturn(generation);
        when(payloadMapper.freezeTerminalEvidence(
                        any(), any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), any(), any()))
                .thenReturn(1);
        when(generationMapper.freezeTerminal(
                        any(), any(), eq(0), anyInt(), any(), any(), any()))
                .thenReturn(1);
        AiConversationGenerationTerminalServiceImpl service =
                new AiConversationGenerationTerminalServiceImpl(
                        generationMapper,
                        payloadMapper,
                        new HybridBase64UrlCodec(),
                        eventPublisher,
                        Clock.fixed(
                                Instant.parse("2026-08-05T12:00:00Z"),
                                ZoneOffset.UTC));

        var result = service.freeze(new AiConversationGenerationTerminalCommand(
                generationId,
                AiConversationGenerationTerminalType.COMPLETED,
                "IMAGE_COMPLETED",
                "",
                "{\"schemaVersion\":2,\"attachments\":[]}",
                new AiConversationUsage(1, 0, 1, 0),
                "STOP",
                "upstream-request",
                "trace-test"));

        assertThat(result.claimed()).isTrue();
        assertThat(result.terminalType()).isEqualTo("CLIENT_CANCELLED");
        assertThat(result.terminalReason()).isEqualTo("USER_STOP");
        verify(payloadMapper).freezeTerminalEvidence(
                eq(generationId),
                eq(""),
                eq("[]"),
                eq(1L),
                eq(1L),
                eq(0L),
                eq(0L),
                isNull(),
                isNull(),
                eq("CLIENT_CANCELLED"),
                eq("upstream-request"),
                any(OffsetDateTime.class));
        ArgumentCaptor<AiConversationGenerationTerminalEvent> event =
                ArgumentCaptor.forClass(AiConversationGenerationTerminalEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().terminalType()).isEqualTo("CLIENT_CANCELLED");
    }

    private static byte[] bytes(int marker) {
        byte[] value = new byte[16];
        value[15] = (byte) marker;
        return value;
    }
}
