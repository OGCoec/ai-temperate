package com.example.temperate.web.user.aiconversation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionCoordinator;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionOperation;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionRequestResult;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionStatus;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionTrigger;
import com.example.temperate.service.user.aiconversation.context.event.AiConversationContextEventService;
import com.example.temperate.service.user.aiconversation.context.usage.AiConversationContextUsage;
import com.example.temperate.service.user.aiconversation.context.usage.AiConversationContextUsageService;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.web.aiconversation.AiConversationPublicId;
import com.example.temperate.web.user.aiconversation.api.AiConversationCompactionRequest;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * 验证上下文用量、异步压缩与会话资源级授权仍由后端服务执行，Controller 只编排 HTTP 契约。
 */
final class AiConversationContextControllerTest {

    private static final String CONVERSATION_PUBLIC_ID =
            "AAAAAAAAAAAAAAAAAAAAAQ";
    private static final String MODEL_PUBLIC_ID = "AAAAAAAAAAE";
    private static final SessionPrincipal PRINCIPAL =
            new SessionPrincipal(7L, "user", "Alice");

    @Test
    void validatedControllerRemainsProxyableBySpring() {
        assertThat(Modifier.isFinal(
                AiConversationContextController.class.getModifiers()))
                .isFalse();
    }

    @Test
    void usageDelegatesOwnedLookupAndDisablesCaching() {
        AiConversationContextUsageService usageService =
                mock(AiConversationContextUsageService.class);
        AiConversationContextUsage usage = usage(false, "IDLE", null);
        when(usageService.getOwned(
                eq(7L), any(byte[].class), eq(CONVERSATION_PUBLIC_ID),
                eq(MODEL_PUBLIC_ID))).thenReturn(usage);
        AiConversationContextController controller = controller(
                usageService, mock(AiConversationCompactionCoordinator.class));

        var response = controller.usage(
                PRINCIPAL, conversationId(), MODEL_PUBLIC_ID);

        assertThat(response.getBody()).isEqualTo(usage);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        verify(usageService).getOwned(
                eq(7L), any(byte[].class), eq(CONVERSATION_PUBLIC_ID),
                eq(MODEL_PUBLIC_ID));
    }

    @Test
    void exactThresholdReturnsAcceptedSingleFlightOperation() {
        AiConversationCompactionCoordinator coordinator =
                mock(AiConversationCompactionCoordinator.class);
        AiConversationContextUsage usage = usage(
                true, "QUEUED", "AAAAAAAAAAAAAAAAAAAAAg");
        AiConversationCompactionOperation operation =
                new AiConversationCompactionOperation(
                        "AAAAAAAAAAAAAAAAAAAAAg",
                        12L,
                        3L,
                        AiConversationCompactionStatus.QUEUED,
                        AiConversationCompactionTrigger.MODEL_SWITCH,
                        OffsetDateTime.parse("2026-08-07T06:45:03Z"),
                        OffsetDateTime.parse("2026-08-07T06:45:03Z"),
                        null,
                        false);
        when(coordinator.requestOwned(
                eq(7L), any(byte[].class), eq(CONVERSATION_PUBLIC_ID),
                eq(MODEL_PUBLIC_ID), any(),
                eq(AiConversationCompactionTrigger.MODEL_SWITCH)))
                .thenReturn(new AiConversationCompactionRequestResult(
                        "QUEUED", operation, usage));
        AiConversationContextController controller = controller(
                mock(AiConversationContextUsageService.class), coordinator);

        var response = controller.compact(
                PRINCIPAL,
                conversationId(),
                "4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6",
                new AiConversationCompactionRequest(MODEL_PUBLIC_ID));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().usage().usagePercent())
                .isEqualByComparingTo("80.0");
    }

    @Test
    void rejectsNonV4CompactionIdempotencyKey() {
        AiConversationContextController controller = controller(
                mock(AiConversationContextUsageService.class),
                mock(AiConversationCompactionCoordinator.class));

        assertThatThrownBy(() -> controller.compact(
                PRINCIPAL,
                conversationId(),
                "019fade7-eae9-72c3-9208-057c793971a7",
                new AiConversationCompactionRequest(MODEL_PUBLIC_ID)))
                .isInstanceOf(AiConversationException.class)
                .hasMessage("Idempotency-Key must be a UUIDv4.");
    }

    private static AiConversationContextController controller(
            AiConversationContextUsageService usageService,
            AiConversationCompactionCoordinator coordinator) {
        return new AiConversationContextController(
                usageService,
                coordinator,
                mock(AiConversationContextEventService.class));
    }

    private static AiConversationPublicId conversationId() {
        return new AiConversationPublicId(
                CONVERSATION_PUBLIC_ID, new byte[16]);
    }

    private static AiConversationContextUsage usage(
            boolean thresholdReached,
            String status,
            String operationPublicId) {
        return new AiConversationContextUsage(
                CONVERSATION_PUBLIC_ID,
                MODEL_PUBLIC_ID,
                thresholdReached ? 800_000L : 200_000L,
                thresholdReached ? 800L : 200L,
                1_000_000L,
                1_000L,
                thresholdReached
                        ? new BigDecimal("80.0")
                        : new BigDecimal("20.0"),
                80,
                thresholdReached,
                false,
                12L,
                status,
                operationPublicId,
                OffsetDateTime.parse("2026-08-07T06:45:03Z"));
    }
}
