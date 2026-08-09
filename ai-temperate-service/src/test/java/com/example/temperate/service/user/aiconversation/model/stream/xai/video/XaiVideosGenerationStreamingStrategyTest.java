package com.example.temperate.service.user.aiconversation.model.stream.xai.video;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.user.aiconversation.config.AiConversationVideoGenerationProperties;
import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelRequest;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.example.temperate.service.user.aiconversation.video.AiConversationGeneratedVideo;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoAspectRatio;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoGenerationOptions;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMode;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoResolution;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 验证视频策略只创建一次任务，随后通过状态 GET 轮询并在终态前先暴露可对账 request ID。
 */
final class XaiVideosGenerationStreamingStrategyTest {

    @Test
    void startsOnceAndPollsUntilDone() {
        XaiVideoClient client = mock(XaiVideoClient.class);
        when(client.start(any())).thenReturn(Mono.just(
                new XaiVideoStartResult("request-1")));
        when(client.poll("request-1"))
                .thenReturn(Mono.just(new XaiVideoPollResult(
                        XaiVideoStatus.PENDING, 40, null, null)))
                .thenReturn(Mono.just(new XaiVideoPollResult(
                        XaiVideoStatus.DONE,
                        100,
                        new AiConversationGeneratedVideo(
                                "request-1",
                                "https://vidgen.x.ai/result.mp4",
                                5_000L,
                                "grok-imagine-video-1.5",
                                false),
                        4_000_000_000L)));
        AiConversationVideoGenerationProperties properties = enabledProperties();
        XaiVideoOperationStrategy operation = new XaiTextToVideoOperationStrategy(
                new ObjectMapper(), properties);
        XaiVideosGenerationStreamingStrategy strategy =
                new XaiVideosGenerationStreamingStrategy(
                        new AiInferenceProperties(
                                true, "https://proxy.example", "test-key",
                                Duration.ofMinutes(1)),
                        properties,
                        registry(operation),
                        client);

        StepVerifier.create(strategy.stream(request()))
                .expectNextMatches(AiConversationModelEvent.VideoRequestAccepted.class::isInstance)
                .expectNextMatches(AiConversationModelEvent.VideoProgress.class::isInstance)
                .expectNextMatches(AiConversationModelEvent.VideoProgress.class::isInstance)
                .expectNextMatches(AiConversationModelEvent.VideoCostEvidence.class::isInstance)
                .expectNextMatches(AiConversationModelEvent.Video.class::isInstance)
                .verifyComplete();

        verify(client, times(1)).start(any());
        verify(client, times(2)).poll("request-1");
    }

    @Test
    void resumesFrozenRequestWithoutCreatingAnotherTask() {
        XaiVideoClient client = mock(XaiVideoClient.class);
        when(client.poll("request-frozen"))
                .thenReturn(Mono.just(new XaiVideoPollResult(
                        XaiVideoStatus.DONE,
                        100,
                        new AiConversationGeneratedVideo(
                                "request-frozen",
                                "https://vidgen.x.ai/result.mp4",
                                5_000L,
                                "grok-imagine-video-1.5",
                                false),
                        4_000_000_000L)));
        AiConversationVideoGenerationProperties properties = enabledProperties();
        XaiVideoOperationStrategy operation = new XaiTextToVideoOperationStrategy(
                new ObjectMapper(), properties);
        XaiVideosGenerationStreamingStrategy strategy =
                new XaiVideosGenerationStreamingStrategy(
                        new AiInferenceProperties(
                                true, "https://proxy.example", "test-key",
                                Duration.ofMinutes(1)),
                        properties,
                        registry(operation),
                        client);

        StepVerifier.create(strategy.stream(request("request-frozen")))
                .expectNextMatches(AiConversationModelEvent.VideoRequestAccepted.class::isInstance)
                .expectNextMatches(AiConversationModelEvent.VideoProgress.class::isInstance)
                .expectNextMatches(AiConversationModelEvent.VideoCostEvidence.class::isInstance)
                .expectNextMatches(AiConversationModelEvent.Video.class::isInstance)
                .verifyComplete();

        verify(client, times(0)).start(any());
        verify(client, times(1)).poll("request-frozen");
    }

    @Test
    void waitsOneConfiguredIntervalBeforeFirstStatusGet() {
        XaiVideoClient client = mock(XaiVideoClient.class);
        when(client.start(any())).thenReturn(Mono.just(
                new XaiVideoStartResult("request-delayed")));
        when(client.poll("request-delayed"))
                .thenReturn(Mono.just(new XaiVideoPollResult(
                        XaiVideoStatus.DONE,
                        100,
                        new AiConversationGeneratedVideo(
                                "request-delayed",
                                "https://vidgen.x.ai/result.mp4",
                                5_000L,
                                "grok-imagine-video-1.5",
                                false),
                        4_000_000_000L)));
        AiConversationVideoGenerationProperties properties =
                enabledProperties(
                        Duration.ofSeconds(5), Duration.ofSeconds(30));
        XaiVideosGenerationStreamingStrategy strategy =
                new XaiVideosGenerationStreamingStrategy(
                        new AiInferenceProperties(
                                true, "https://proxy.example", "test-key",
                                Duration.ofMinutes(1)),
                        properties,
                        registry(new XaiTextToVideoOperationStrategy(
                                new ObjectMapper(), properties)),
                        client);

        StepVerifier.withVirtualTime(() -> strategy.stream(request()))
                .expectNextMatches(
                        AiConversationModelEvent.VideoRequestAccepted.class::isInstance)
                .expectNoEvent(Duration.ofSeconds(4))
                .thenAwait(Duration.ofSeconds(1))
                .expectNextMatches(AiConversationModelEvent.VideoProgress.class::isInstance)
                .expectNextMatches(
                        AiConversationModelEvent.VideoCostEvidence.class::isInstance)
                .expectNextMatches(AiConversationModelEvent.Video.class::isInstance)
                .verifyComplete();

        verify(client, times(1)).poll("request-delayed");
    }

    @Test
    void doesNotOverflowWhenStatusGetExceedsPollInterval() {
        XaiVideoClient client = mock(XaiVideoClient.class);
        when(client.start(any())).thenReturn(Mono.just(
                new XaiVideoStartResult("request-slow")));
        when(client.poll("request-slow"))
                .thenReturn(Mono.defer(() ->
                        Mono.delay(Duration.ofSeconds(6))
                                .thenReturn(new XaiVideoPollResult(
                                        XaiVideoStatus.PENDING, 40, null, null))))
                .thenReturn(Mono.just(new XaiVideoPollResult(
                        XaiVideoStatus.DONE,
                        100,
                        new AiConversationGeneratedVideo(
                                "request-slow",
                                "https://vidgen.x.ai/result.mp4",
                                5_000L,
                                "grok-imagine-video-1.5",
                                false),
                        4_000_000_000L)));
        AiConversationVideoGenerationProperties properties =
                enabledProperties(
                        Duration.ofSeconds(5), Duration.ofSeconds(30));
        XaiVideosGenerationStreamingStrategy strategy =
                new XaiVideosGenerationStreamingStrategy(
                        new AiInferenceProperties(
                                true, "https://proxy.example", "test-key",
                                Duration.ofMinutes(1)),
                        properties,
                        registry(new XaiTextToVideoOperationStrategy(
                                new ObjectMapper(), properties)),
                        client);

        StepVerifier.withVirtualTime(() -> strategy.stream(request()))
                .expectNextMatches(
                        AiConversationModelEvent.VideoRequestAccepted.class::isInstance)
                .thenAwait(Duration.ofSeconds(11))
                .expectNextMatches(AiConversationModelEvent.VideoProgress.class::isInstance)
                .expectNoEvent(Duration.ofSeconds(4))
                .thenAwait(Duration.ofSeconds(1))
                .expectNextMatches(AiConversationModelEvent.VideoProgress.class::isInstance)
                .expectNextMatches(
                        AiConversationModelEvent.VideoCostEvidence.class::isInstance)
                .expectNextMatches(AiConversationModelEvent.Video.class::isInstance)
                .verifyComplete();

        verify(client, times(1)).start(any());
        verify(client, times(2)).poll("request-slow");
    }

    private static AiConversationStreamingRequest request() {
        return request(null);
    }

    private static AiConversationStreamingRequest request(String resumeRequestId) {
        AiConversationVideoGenerationOptions video =
                new AiConversationVideoGenerationOptions(
                        AiConversationVideoMode.TEXT_TO_VIDEO,
                        5,
                        AiConversationVideoResolution.P720,
                        AiConversationVideoAspectRatio.RATIO_16_9,
                        List.of(),
                        0L,
                        0,
                        0,
                        null);
        AiConversationPromptSnapshot prompt = new AiConversationPromptSnapshot(
                "system", null, null, List.of(),
                new AiConversationContent("生成海边日落", List.of()),
                "generation", 10L, false);
        return new AiConversationStreamingRequest(
                new AiConversationModelRequest(
                        AiModelProvider.XAI,
                        "grok-imagine-video-1.5",
                        1L,
                        AiConversationReasoningEffort.MEDIUM,
                        prompt,
                        null,
                        (short) 0,
                        List.of(),
                        video,
                        List.of()),
                AiConversationWebSearchMode.OFF,
                null,
                resumeRequestId);
    }

    private static XaiVideoOperationStrategyRegistry registry(
            XaiVideoOperationStrategy text) {
        return new XaiVideoOperationStrategyRegistry(Map.of(
                "text", text,
                "image", stub(AiConversationVideoMode.IMAGE_TO_VIDEO),
                "reference", stub(AiConversationVideoMode.REFERENCE_TO_VIDEO),
                "edit", stub(AiConversationVideoMode.VIDEO_EDIT),
                "extend", stub(AiConversationVideoMode.VIDEO_EXTEND)));
    }

    private static XaiVideoOperationStrategy stub(AiConversationVideoMode mode) {
        return new XaiVideoOperationStrategy() {
            @Override
            public AiConversationVideoMode mode() {
                return mode;
            }

            @Override
            public XaiVideoStartRequest buildRequest(
                    XaiVideoOperationContext context) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static AiConversationVideoGenerationProperties enabledProperties() {
        return enabledProperties(
                Duration.ofMillis(1), Duration.ofSeconds(1));
    }

    private static AiConversationVideoGenerationProperties enabledProperties(
            Duration pollInterval,
            Duration maximumPollingDuration) {
        AiConversationVideoGenerationProperties defaults =
                AiConversationVideoGenerationProperties.officialDefaults();
        return new AiConversationVideoGenerationProperties(
                true,
                pollInterval,
                maximumPollingDuration,
                defaults.maximumResponseJsonBytes(),
                defaults.endpoints(),
                defaults.version15(),
                defaults.legacy(),
                new AiConversationVideoGenerationProperties.FunctionCompute(
                        "https://fc.example/invoke",
                        "test-only-secret-with-sufficient-length",
                        Duration.ofSeconds(1),
                        "ai/video/",
                        2_147_483_648L,
                        List.of("vidgen.x.ai")));
    }
}
