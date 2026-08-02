package com.example.temperate.service.user.aiconversation.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.ai.AiModelUsageDetailMapper;
import com.example.temperate.mapper.ai.AiModelUsageMapper;
import com.example.temperate.model.ai.entity.AiModelUsage;
import com.example.temperate.model.ai.entity.AiModelUsageDetail;
import com.example.temperate.model.ai.enums.AiModelBillingStatus;
import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.context.AiConversationInterruptionSource;
import com.example.temperate.service.user.aiconversation.response.impl.AiConversationDirectResponseCancellationServiceImpl;
import com.example.temperate.service.user.aiconversation.security.AiConversationIdempotencyHasher;
import com.example.temperate.service.user.aiconversation.response.rabbit.AiConversationDirectResponseControlPublisher;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 验证直接 SSE Stop 的用户归属、Reserved 状态、Redis 意图和跨实例路由边界。
 */
final class AiConversationDirectResponseCancellationServiceTest {

    @Test
    void reservedUsageIsCancelledAndRoutedToItsOwner() {
        AiConversationIdempotencyHasher hasher = new AiConversationIdempotencyHasher(
                new byte[32]);
        AiModelUsageDetailMapper details = mock(AiModelUsageDetailMapper.class);
        AiModelUsageMapper usages = mock(AiModelUsageMapper.class);
        AiConversationDirectResponseControlStore controlStore =
                mock(AiConversationDirectResponseControlStore.class);
        AiConversationDirectResponseActiveRegistry registry =
                mock(AiConversationDirectResponseActiveRegistry.class);
        AiConversationDirectResponseControlPublisher publisher =
                mock(AiConversationDirectResponseControlPublisher.class);
        AiConversationAsyncGenerationProperties properties = properties();
        UUID idempotencyKey = UUID.randomUUID();
        AiModelUsageDetail detail = new AiModelUsageDetail();
        detail.setUsageId(new byte[] {1});
        AiModelUsage usage = new AiModelUsage();
        usage.setLoginIdentityId(7L);
        usage.setBillingStatus(AiModelBillingStatus.RESERVED.code());
        when(details.findByIdempotencyDigest(any())).thenReturn(detail);
        when(usages.findById(new byte[] {1})).thenReturn(usage);
        when(registry.cancel(any(), eq(AiConversationInterruptionSource.USER_STOP)))
                .thenReturn(false);
        when(controlStore.findOwner(any())).thenReturn(Optional.of("node-b"));

        AiConversationDirectResponseCancellationService service =
                new AiConversationDirectResponseCancellationServiceImpl(
                        details,
                        usages,
                        hasher,
                        controlStore,
                        registry,
                        publisher,
                        properties);

        assertThat(service.requestUserStop(7L, idempotencyKey, "trace-1"))
                .isEqualTo(AiConversationDirectResponseCancellationStatus.CANCEL_REQUESTED);
        verify(controlStore).requestUserStop(any(), eq(Duration.ofMinutes(16)));
        verify(publisher).publishCancelRequested(any(), eq("node-b"), eq("trace-1"));
    }

    @Test
    void terminalUsageDoesNotRouteAnotherCancellation() {
        AiConversationIdempotencyHasher hasher = new AiConversationIdempotencyHasher(
                new byte[32]);
        AiModelUsageDetailMapper details = mock(AiModelUsageDetailMapper.class);
        AiModelUsageMapper usages = mock(AiModelUsageMapper.class);
        AiConversationDirectResponseControlStore controlStore =
                mock(AiConversationDirectResponseControlStore.class);
        AiConversationDirectResponseActiveRegistry registry =
                mock(AiConversationDirectResponseActiveRegistry.class);
        AiConversationDirectResponseControlPublisher publisher =
                mock(AiConversationDirectResponseControlPublisher.class);
        AiModelUsageDetail detail = new AiModelUsageDetail();
        detail.setUsageId(new byte[] {2});
        AiModelUsage usage = new AiModelUsage();
        usage.setLoginIdentityId(7L);
        usage.setBillingStatus(AiModelBillingStatus.SETTLED.code());
        when(details.findByIdempotencyDigest(any())).thenReturn(detail);
        when(usages.findById(new byte[] {2})).thenReturn(usage);

        AiConversationDirectResponseCancellationService service =
                new AiConversationDirectResponseCancellationServiceImpl(
                        details,
                        usages,
                        hasher,
                        controlStore,
                        registry,
                        publisher,
                        properties());

        assertThat(service.requestUserStop(7L, UUID.randomUUID(), "trace-2"))
                .isEqualTo(AiConversationDirectResponseCancellationStatus.ALREADY_TERMINAL);
        verify(publisher, org.mockito.Mockito.never())
                .publishCancelRequested(any(), any(), any());
    }

    private static AiConversationAsyncGenerationProperties properties() {
        return new AiConversationAsyncGenerationProperties(
                false,
                "node-a",
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                Duration.ofHours(1),
                1,
                Duration.ofMinutes(15));
    }
}
