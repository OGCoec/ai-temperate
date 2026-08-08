package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.mapper.ai.AiConversationGenerationPayloadMapper;
import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.model.ai.entity.AiConversationGenerationPayload;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentCategory;
import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingBoundary;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingClock;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingContext;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingDiagnosticService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingPath;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTransportDiagnosticService;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationObserverService;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationObserverStateService;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationOutputSnapshot;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationOutputStore;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationOutputSubscriber;
import com.example.temperate.service.user.aiconversation.generation.observer.impl.AiConversationGenerationObserverServiceImpl;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewBroker;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewData;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.example.temperate.service.user.aiconversation.response.AiConversationStreamEvent;
import com.example.temperate.service.user.aiconversation.video.AiConversationPersistedVideoResult;
import com.example.temperate.service.user.aiconversation.video.AiConversationPersistedVideoResultCodec;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoGenerationStage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * 验证异步 SSE Observer 为独立 Redis 订阅建立时序会话，并且只把 delta 正文长度交给事件就绪边界统计。
 */
final class AiConversationGenerationObserverTimingTest {

    private static final HybridBase64UrlCodec HYBRID_ID_CODEC = new HybridBase64UrlCodec();
    private static final PublicIdCodec PUBLIC_ID_CODEC = new PublicIdCodec();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void observerInstallsIndependentTimingContextAndCountsOnlyDeltaText() throws Exception {
        byte[] generationId = bytes(1);
        byte[] usageId = bytes(2);
        byte[] conversationId = bytes(3);
        AiConversationGeneration generation = generation(
                generationId, usageId, conversationId);
        AiConversationGenerationMapper generationMapper = mock(AiConversationGenerationMapper.class);
        AiConversationStreamTimingDiagnosticService timingDiagnosticService =
                mock(AiConversationStreamTimingDiagnosticService.class);
        when(generationMapper.attachObserver(any(), eq(42L), any(Integer.class), any()))
                .thenReturn(1);
        when(generationMapper.findOwned(generationId, 42L)).thenReturn(generation);
        when(timingDiagnosticService.observeBoundary(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(timingDiagnosticService.withSession(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AiConversationGenerationObserverService service =
                new AiConversationGenerationObserverServiceImpl(
                        generationMapper,
                        mock(AiConversationGenerationOutputStore.class),
                        mock(AiConversationGenerationOutputSubscriber.class),
                        mock(AiConversationGenerationObserverStateService.class),
                        asyncProperties(),
                        HYBRID_ID_CODEC,
                        PUBLIC_ID_CODEC,
                        OBJECT_MAPPER,
                        timingDiagnosticService,
                        fixedClock(),
                        mock(AiConversationMetrics.class),
                        Clock.systemUTC());

        service.observe(42L, generationId);

        ArgumentCaptor<AiConversationStreamTimingContext> contextCaptor =
                ArgumentCaptor.forClass(AiConversationStreamTimingContext.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ToIntFunction<AiConversationStreamEvent>> characterCounterCaptor =
                ArgumentCaptor.forClass(ToIntFunction.class);
        verify(timingDiagnosticService).withSession(any(), contextCaptor.capture());
        verify(timingDiagnosticService).observeBoundary(
                any(),
                eq(AiConversationStreamTimingBoundary.SSE_EVENT_READY),
                characterCounterCaptor.capture());

        AiConversationStreamTimingContext context = contextCaptor.getValue();
        assertThat(context.usagePublicId()).isEqualTo(HYBRID_ID_CODEC.encode(usageId));
        assertThat(context.conversationPublicId())
                .isEqualTo(HYBRID_ID_CODEC.encode(conversationId));
        assertThat(context.modelPublicId()).isEqualTo(PUBLIC_ID_CODEC.encode(7L));
        assertThat(context.path())
                .isEqualTo(AiConversationStreamTimingPath.ASYNC_GENERATION_OBSERVER);
        assertThat(context.startedNanos()).isEqualTo(456L);

        ToIntFunction<AiConversationStreamEvent> characterCounter = characterCounterCaptor.getValue();
        JsonNode text = OBJECT_MAPPER.readTree("{\"text\":\"三个字\"}");
        assertThat(characterCounter.applyAsInt(new AiConversationStreamEvent("delta", text)))
                .isEqualTo(3);
        assertThat(characterCounter.applyAsInt(new AiConversationStreamEvent("heartbeat", text)))
                .isZero();
        assertThat(characterCounter.applyAsInt(new AiConversationStreamEvent(
                "delta", OBJECT_MAPPER.readTree("{}"))))
                .isZero();
    }

    @Test
    void imagePreviewProducesP6ThenP7AndDiagnosticFailureDoesNotBreakSse() {
        byte[] generationId = bytes(4);
        AiConversationGeneration generation = generation(
                generationId, bytes(5), bytes(6));
        AiConversationGenerationMapper generationMapper = mock(
                AiConversationGenerationMapper.class);
        AiConversationGenerationOutputStore outputStore = mock(
                AiConversationGenerationOutputStore.class);
        AiConversationGenerationOutputSubscriber outputSubscriber = mock(
                AiConversationGenerationOutputSubscriber.class);
        AiConversationStreamTimingDiagnosticService timingDiagnosticService = mock(
                AiConversationStreamTimingDiagnosticService.class);
        AiConversationImagePreviewBroker previewBroker = mock(
                AiConversationImagePreviewBroker.class);
        when(generationMapper.attachObserver(any(), eq(42L), any(Integer.class), any()))
                .thenReturn(1);
        when(generationMapper.findOwned(generationId, 42L)).thenReturn(generation);
        when(outputSubscriber.subscribe(any(), any())).thenReturn(() -> {
        });
        when(outputStore.snapshot(any())).thenReturn(
                new AiConversationGenerationOutputSnapshot(0L, "", null, null));
        AiConversationImagePreviewData previewData = new AiConversationImagePreviewData(
                "image-0",
                "PARTIAL",
                (short) 0,
                (short) 1,
                "image/webp",
                1024,
                1024,
                "THUMBNAIL",
                true,
                "preview-must-not-enter-diagnostics");
        when(previewBroker.events(any())).thenReturn(Flux.just(
                new AiConversationStreamEvent("image-preview", previewData)));
        when(timingDiagnosticService.observeBoundary(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(timingDiagnosticService.withSession(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ArrayList<Map<String, ?>> diagnostics = new ArrayList<>();
        AiConversationStreamTransportDiagnosticService throwingDiagnostics =
                (context, event, details) -> {
                    if ("ai_image_stream_checkpoint".equals(event)) {
                        diagnostics.add(Map.copyOf(details));
                    }
                    throw new IllegalStateException("diagnostic unavailable");
                };
        AiConversationGenerationObserverService service =
                new AiConversationGenerationObserverServiceImpl(
                        generationMapper,
                        outputStore,
                        outputSubscriber,
                        mock(AiConversationGenerationObserverStateService.class),
                        asyncProperties(),
                        HYBRID_ID_CODEC,
                        PUBLIC_ID_CODEC,
                        OBJECT_MAPPER,
                        timingDiagnosticService,
                        fixedClock(),
                        mock(AiConversationMetrics.class),
                        Clock.systemUTC(),
                        throwingDiagnostics,
                        previewBroker);

        StepVerifier.create(service.observe(42L, generationId).events()
                        .filter(event -> "image-preview".equals(event.name()))
                        .take(1))
                .expectNextMatches(event -> event.data() == previewData)
                .verifyComplete();

        assertThat(diagnostics)
                .extracting(details -> String.valueOf(
                        details.get("checkpoint")))
                .containsExactly("P6_OBSERVER_RECEIVED", "P7_SSE_READY");
        assertThat(diagnostics.toString())
                .doesNotContain("preview-must-not-enter-diagnostics");
    }

    @Test
    void rebuildsVideoReadyFromDatabaseEnvelopeWhenRedisTerminalIsMissing() {
        byte[] generationId = bytes(7);
        AiConversationGeneration generation = generation(
                generationId, bytes(8), bytes(9));
        generation.setGenerationStatus(AiConversationGenerationStatus.SETTLED.code());
        generation.setTerminalType(AiConversationGenerationTerminalType.COMPLETED.name());
        generation.setTerminalReason("VIDEO_OSS_READY");
        generation.setVideoStage(AiConversationVideoGenerationStage.SUCCEEDED.name());
        AiConversationGenerationMapper generationMapper = mock(
                AiConversationGenerationMapper.class);
        AiConversationGenerationPayloadMapper payloadMapper = mock(
                AiConversationGenerationPayloadMapper.class);
        AiConversationGenerationOutputStore outputStore = mock(
                AiConversationGenerationOutputStore.class);
        AiConversationGenerationOutputSubscriber outputSubscriber = mock(
                AiConversationGenerationOutputSubscriber.class);
        AiConversationStreamTimingDiagnosticService timing = mock(
                AiConversationStreamTimingDiagnosticService.class);
        AiConversationImagePreviewBroker previewBroker = mock(
                AiConversationImagePreviewBroker.class);
        when(generationMapper.attachObserver(any(), eq(42L), any(Integer.class), any()))
                .thenReturn(1);
        when(generationMapper.findOwned(generationId, 42L)).thenReturn(generation);
        when(outputSubscriber.subscribe(any(), any())).thenReturn(() -> {
        });
        when(outputStore.snapshot(any())).thenReturn(
                new AiConversationGenerationOutputSnapshot(0L, "", null, null));
        when(timing.observeBoundary(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(timing.withSession(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(previewBroker.events(any())).thenReturn(Flux.empty());
        AiConversationPersistedVideoResultCodec codec =
                new AiConversationPersistedVideoResultCodec(OBJECT_MAPPER);
        AiConversationGenerationPayload payload = new AiConversationGenerationPayload();
        payload.setConversationMessageId(77L);
        payload.setAssistantAttachmentsJson(codec.encode(
                new AiConversationPersistedVideoResult(
                        AiConversationAttachment.available(
                                "video-attachment",
                                "generated-video.mp4",
                                "video/mp4",
                                4096L,
                                AiConversationAttachmentCategory.VIDEO,
                                "https://oss.example/generated-video.mp4"),
                        5_000L,
                        1280,
                        720,
                        "video/mp4",
                        4096L,
                        "h264",
                        "ai/video/generated-video.mp4",
                        "ALIYUN_OSS")));
        when(payloadMapper.findByGenerationId(generationId)).thenReturn(payload);
        AiConversationGenerationObserverService service =
                new AiConversationGenerationObserverServiceImpl(
                        generationMapper,
                        outputStore,
                        outputSubscriber,
                        mock(AiConversationGenerationObserverStateService.class),
                        asyncProperties(),
                        HYBRID_ID_CODEC,
                        PUBLIC_ID_CODEC,
                        OBJECT_MAPPER,
                        timing,
                        fixedClock(),
                        mock(AiConversationMetrics.class),
                        Clock.systemUTC(),
                        AiConversationStreamTransportDiagnosticService.noOp(),
                        previewBroker,
                        payloadMapper,
                        codec);

        StepVerifier.create(service.observe(42L, generationId).events())
                .expectNextMatches(event -> "snapshot".equals(event.name()))
                .expectNextMatches(event -> "video_ready".equals(event.name())
                        && event.data() instanceof Map<?, ?> data
                        && ((java.util.List<?>) data.get("attachments")).size() == 1)
                .verifyComplete();
    }

    private static AiConversationGeneration generation(
            byte[] generationId,
            byte[] usageId,
            byte[] conversationId) {
        AiConversationGeneration generation = new AiConversationGeneration();
        generation.setId(generationId);
        generation.setUsageId(usageId);
        generation.setConversationId(conversationId);
        generation.setModelId(7L);
        generation.setGenerationStatus(AiConversationGenerationStatus.RUNNING.code());
        generation.setObserverEpoch(8L);
        return generation;
    }

    private static AiConversationAsyncGenerationProperties asyncProperties() {
        return new AiConversationAsyncGenerationProperties(
                true,
                "instance-test",
                Duration.ofMillis(250),
                Duration.ofSeconds(15),
                Duration.ofHours(24),
                1,
                Duration.ofSeconds(2));
    }

    private static AiConversationStreamTimingClock fixedClock() {
        return () -> 456L;
    }

    private static byte[] bytes(int marker) {
        byte[] value = new byte[16];
        value[15] = (byte) marker;
        return value;
    }
}
