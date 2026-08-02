package com.example.temperate.service.user.aiconversation.diagnostic.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamClientDiagnostic;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamClientDiagnosticRateLimitService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamClientDiagnosticService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingClock;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingContext;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingPath;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTransportDiagnosticService;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationService;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationView;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 验证浏览器汇总诊断会先校验当前用户对 Generation 的归属，再只写入安全的计数和时间字段。
 */
final class AiConversationStreamClientDiagnosticServiceImplTest {

    @Test
    void recordsOwnedGenerationSummaryWithoutModelText() {
        AiConversationGenerationService generationService = mock(
                AiConversationGenerationService.class);
        AiConversationStreamClientDiagnosticRateLimitService rateLimitService = mock(
                AiConversationStreamClientDiagnosticRateLimitService.class);
        AiConversationStreamTransportDiagnosticService transport = mock(
                AiConversationStreamTransportDiagnosticService.class);
        AtomicReference<AiConversationStreamTimingContext> context = new AtomicReference<>();
        AtomicReference<Map<String, ?>> details = new AtomicReference<>();
        when(generationService.getOwned(eq(7L), any(byte[].class))).thenReturn(view());
        when(rateLimitService.tryAcquire("AZ-vpV3kfag70-0EMMUETQ")).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            context.set(invocation.getArgument(0));
            details.set(invocation.getArgument(2));
            return null;
        }).when(transport).record(any(), any(), any());
        AiConversationStreamClientDiagnosticService service =
                new AiConversationStreamClientDiagnosticServiceImpl(
                        generationService,
                        rateLimitService,
                        transport,
                        () -> 100L);

        service.record(7L, new byte[] {1, 2, 3}, diagnostic());

        verify(rateLimitService).tryAcquire("AZ-vpV3kfag70-0EMMUETQ");
        assertThat(context.get().path()).isEqualTo(AiConversationStreamTimingPath.BROWSER_CLIENT);
        assertThat(context.get().usagePublicId()).isEqualTo("AZ-50wCZAQGBuCvbSqIYsA");
        assertThat(details.get().get("firstDeltaMs")).isEqualTo(250L);
        assertThat(details.get()).doesNotContainKey("text");
    }

    private static AiConversationStreamClientDiagnostic diagnostic() {
        return new AiConversationStreamClientDiagnostic(
                "AZ-50wCZAQGBuCvbSqIYsA",
                "4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6",
                "COMPLETE",
                20L,
                30L,
                40L,
                50L,
                250L,
                260L,
                3L,
                128L,
                4L,
                2L,
                15L,
                1L,
                0L);
    }

    private static AiConversationGenerationView view() {
        return new AiConversationGenerationView(
                "AZ-vpV3kfag70-0EMMUETQ",
                "AZ-vpV3kfag70-0EMMUETQ",
                "AZ-50wCZAQGBuCvbSqIYsA",
                "RUNNING",
                "ATTACHED",
                1L,
                null,
                null,
                null,
                0,
                OffsetDateTime.now(),
                OffsetDateTime.now());
    }
}
