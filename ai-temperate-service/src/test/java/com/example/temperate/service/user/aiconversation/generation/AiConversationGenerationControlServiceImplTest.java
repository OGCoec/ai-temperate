package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.mapper.ai.AiConversationGenerationPayloadMapper;
import com.example.temperate.mapper.ai.AiModelUsageDetailMapper;
import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.generation.worker.impl.AiConversationGenerationControlServiceImpl;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * 验证视频上游 request ID 在创建受理后立即冻结，并阻止不同任务 ID 覆盖恢复游标。
 */
final class AiConversationGenerationControlServiceImplTest {

    @Test
    void bindsUpstreamRequestIdUsingCompareAndSetMapperContract() {
        AiConversationGenerationPayloadMapper payloadMapper =
                mock(AiConversationGenerationPayloadMapper.class);
        byte[] generationId = new byte[] {1, 2, 3};
        when(payloadMapper.bindUpstreamRequestId(
                eq(generationId), eq("request-1"), any(OffsetDateTime.class)))
                .thenReturn(1);
        AiConversationGenerationControlServiceImpl service = service(payloadMapper);

        service.bindUpstreamRequestId(generationId, "request-1");

        verify(payloadMapper).bindUpstreamRequestId(
                eq(generationId), eq("request-1"), any(OffsetDateTime.class));
    }

    @Test
    void rejectsConflictingFrozenUpstreamRequestId() {
        AiConversationGenerationPayloadMapper payloadMapper =
                mock(AiConversationGenerationPayloadMapper.class);
        byte[] generationId = new byte[] {1, 2, 3};
        when(payloadMapper.bindUpstreamRequestId(
                eq(generationId), eq("request-2"), any(OffsetDateTime.class)))
                .thenReturn(0);
        AiConversationGenerationControlServiceImpl service = service(payloadMapper);

        assertThatThrownBy(() -> service.bindUpstreamRequestId(
                generationId, "request-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicts");
    }

    private static AiConversationGenerationControlServiceImpl service(
            AiConversationGenerationPayloadMapper payloadMapper) {
        return new AiConversationGenerationControlServiceImpl(
                mock(AiConversationGenerationMapper.class),
                payloadMapper,
                mock(AiModelUsageDetailMapper.class),
                mock(AiConversationAsyncGenerationProperties.class),
                Clock.systemUTC());
    }
}
