package com.example.temperate.service.user.aiconversation.model.stream.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.service.user.aiconversation.config.AiConversationImageGenerationProperties;
import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingContext;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingPath;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTransportDiagnosticService;
import com.example.temperate.service.user.aiconversation.diagnostic.impl.AiConversationStreamFailureClassifierImpl;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAction;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageQuality;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelRequest;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingDiagnosticContext;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.example.temperate.service.user.aiconversation.runtime.AiConversationRuntimeFaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 验证图片上游流的诊断实现保留完整检查点，同时禁止把图片正文写入诊断字段。
 */
final class ImagesGenerationStreamingStrategyDiagnosticTest {

    private static final byte[] WEBP_IMAGE = new byte[] {
            'R', 'I', 'F', 'F', 0x04, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P'};

    @Test
    void declaresUpstreamCheckpointsAndTerminalSummary() throws IOException {
        String source = Files.readString(findProjectRoot().resolve(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/"
                        + "aiconversation/model/stream/impl/"
                        + "ImagesGenerationStreamingStrategy.java"),
                StandardCharsets.UTF_8);

        assertThat(source)
                .contains("P0_CHILD_SUBSCRIBE")
                .contains("P1_HTTP_RESPONSE")
                .contains("P2_SSE_EVENT_DECODED")
                .contains("P3_EVENT_MAPPED")
                .contains("P3_EVENT_MAPPING_FAILED")
                .contains("ai_image_stream_summary")
                .doesNotContain("Map.entry(\"base64\"")
                .doesNotContain("Map.entry(\"b64_json\"")
                .doesNotContain("Map.entry(\"partial_image_b64\"");
    }

    @Test
    void recordsCompleteCheckpointChainWithoutCapturingBase64() {
        String base64 = Base64.getEncoder().encodeToString(WEBP_IMAGE);
        String body = "event: image_generation.partial_image\n"
                + "data: {\"type\":\"image_generation.partial_image\","
                + "\"partial_image_index\":0,\"b64_json\":\""
                + base64 + "\"}\n\n"
                + "event: image_generation.completed\n"
                + "data: {\"type\":\"image_generation.completed\","
                + "\"id\":\"request-1\",\"b64_json\":\"" + base64 + "\","
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}\n\n";
        ArrayList<DiagnosticRecord> records = new ArrayList<>();
        ImagesGenerationStreamingStrategy strategy = strategy(
                "text/event-stream", body, records);

        StepVerifier.create(strategy.stream(request()))
                .expectNextCount(3)
                .verifyComplete();

        assertThat(records)
                .extracting(DiagnosticRecord::checkpoint)
                .containsSubsequence(
                        "P0_CHILD_SUBSCRIBE",
                        "P1_HTTP_RESPONSE",
                        "P2_SSE_EVENT_DECODED",
                        "P3_EVENT_MAPPED",
                        "P2_SSE_EVENT_DECODED",
                        "P3_EVENT_MAPPED",
                        "SUMMARY");
        assertThat(records.toString()).doesNotContain(base64);
    }

    @Test
    void recordsHttpCheckpointBeforeRejectingNonSseResponse() {
        ArrayList<DiagnosticRecord> records = new ArrayList<>();
        ImagesGenerationStreamingStrategy strategy = strategy(
                "application/json", "{}", records);

        StepVerifier.create(strategy.stream(request()))
                .expectError()
                .verify();

        assertThat(records)
                .extracting(DiagnosticRecord::checkpoint)
                .contains("P0_CHILD_SUBSCRIBE", "P1_HTTP_RESPONSE", "SUMMARY");
    }

    @Test
    void diagnosticFailureDoesNotTerminateImageBusinessStream() {
        String base64 = Base64.getEncoder().encodeToString(WEBP_IMAGE);
        String body = "event: image_generation.completed\n"
                + "data: {\"type\":\"image_generation.completed\","
                + "\"id\":\"request-1\",\"b64_json\":\"" + base64 + "\","
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}\n\n";
        ImagesGenerationStreamingStrategy strategy = strategy(
                "text/event-stream",
                body,
                (context, event, details) -> {
                    throw new IllegalStateException("diagnostic unavailable");
                });

        StepVerifier.create(strategy.stream(request()))
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void replacementDiagnosticBeanNeverReceivesUnknownUpstreamTokens() {
        String secretEvent = "eyJhbGciOiJIUzI1NiJ9.secret.signature";
        String secretType = "https://signed.example/image?token=secret";
        String body = "event: " + secretEvent + "\n"
                + "data: {\"type\":\"" + secretType + "\"}\n\n";
        ArrayList<DiagnosticRecord> records = new ArrayList<>();
        ImagesGenerationStreamingStrategy strategy = strategy(
                "text/event-stream; token=secret", body, records);

        StepVerifier.create(strategy.stream(request()))
                .verifyComplete();

        assertThat(records.toString())
                .contains("upstreamEventName=unknown")
                .contains("upstreamJsonType=unknown")
                .contains("responseContentType=text/event-stream")
                .doesNotContain(secretEvent)
                .doesNotContain(secretType)
                .doesNotContain("token=secret");
    }

    @Test
    void linkageFailureBecomesControlledSystemErrorAfterP2() {
        String body = "event: image_generation.completed\n"
                + "data: {\"type\":\"image_generation.completed\"}\n\n";
        ArrayList<DiagnosticRecord> records = new ArrayList<>();
        OpenAiImagesGenerationEventMapper mapper = mock(
                OpenAiImagesGenerationEventMapper.class);
        AiConversationRuntimeFaultService runtimeFaultService = mock(
                AiConversationRuntimeFaultService.class);
        NoClassDefFoundError linkageFailure = new NoClassDefFoundError(
                "AiConversationGeneratedImagePhase");
        AiConversationException controlledFailure = new AiConversationException(
                AiConversationErrorCode.AI_RUNTIME_LINKAGE_FAILED,
                "AI 服务运行环境异常",
                false,
                linkageFailure);
        when(mapper.mapDetailed(any(), any(), eq((short) 0)))
                .thenThrow(linkageFailure);
        when(runtimeFaultService.imageEventMappingFailure(
                eq("generation"), eq((short) 0), same(linkageFailure)))
                .thenReturn(controlledFailure);
        ImagesGenerationStreamingStrategy strategy = strategy(
                "text/event-stream",
                body,
                (context, event, details) -> records.add(
                        new DiagnosticRecord(event, java.util.Map.copyOf(details))),
                mapper,
                runtimeFaultService);

        StepVerifier.create(strategy.stream(request()))
                .expectErrorSatisfies(failure -> assertThat(failure)
                        .isSameAs(controlledFailure))
                .verify();

        assertThat(records)
                .extracting(DiagnosticRecord::checkpoint)
                .containsSubsequence(
                        "P0_CHILD_SUBSCRIBE",
                        "P1_HTTP_RESPONSE",
                        "P2_SSE_EVENT_DECODED",
                        "P3_EVENT_MAPPING_FAILED",
                        "SUMMARY");
        verify(runtimeFaultService).imageEventMappingFailure(
                eq("generation"), eq((short) 0), same(linkageFailure));
    }

    @Test
    void virtualMachineErrorsAreNotConvertedIntoControlledImageFailures() {
        String body = "event: image_generation.completed\n"
                + "data: {\"type\":\"image_generation.completed\"}\n\n";
        OpenAiImagesGenerationEventMapper mapper = mock(
                OpenAiImagesGenerationEventMapper.class);
        AiConversationRuntimeFaultService runtimeFaultService = mock(
                AiConversationRuntimeFaultService.class);
        OutOfMemoryError fatalFailure = new OutOfMemoryError(
                "test-only-fatal-error");
        when(mapper.mapDetailed(any(), any(), eq((short) 0)))
                .thenThrow(fatalFailure);
        ImagesGenerationStreamingStrategy strategy = strategy(
                "text/event-stream",
                body,
                AiConversationStreamTransportDiagnosticService.noOp(),
                mapper,
                runtimeFaultService);

        assertThatThrownBy(() -> strategy.stream(request()).blockLast())
                .isSameAs(fatalFailure);
        verifyNoInteractions(runtimeFaultService);
    }

    private static ImagesGenerationStreamingStrategy strategy(
            String contentType,
            String body,
            ArrayList<DiagnosticRecord> records) {
        return strategy(
                contentType,
                body,
                (context, event, details) -> records.add(
                        new DiagnosticRecord(
                                event, java.util.Map.copyOf(details))));
    }

    private static ImagesGenerationStreamingStrategy strategy(
            String contentType,
            String body,
            AiConversationStreamTransportDiagnosticService diagnosticService) {
        ObjectMapper objectMapper = new ObjectMapper();
        AiConversationImageGenerationProperties imageProperties =
                imageProperties();
        return strategy(
                contentType,
                body,
                diagnosticService,
                new OpenAiImagesGenerationEventMapper(
                        objectMapper, imageProperties.maximumDecodedImageBytes()),
                AiConversationRuntimeFaultService.withoutAvailabilitySignal());
    }

    private static ImagesGenerationStreamingStrategy strategy(
            String contentType,
            String body,
            AiConversationStreamTransportDiagnosticService diagnosticService,
            OpenAiImagesGenerationEventMapper eventMapper,
            AiConversationRuntimeFaultService runtimeFaultService) {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(ignored -> Mono.just(ClientResponse.create(
                                HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, contentType)
                        .body(body)
                        .build()));
        ObjectMapper objectMapper = new ObjectMapper();
        return new ImagesGenerationStreamingStrategy(
                builder,
                new AiInferenceProperties(
                        true,
                        "http://cli-proxy.test",
                        "test-only-key",
                        Duration.ofSeconds(5)),
                imageProperties(),
                objectMapper,
                new AiConversationStreamFailureClassifierImpl(),
                diagnosticService,
                runtimeFaultService,
                eventMapper);
    }

    private static AiConversationImageGenerationProperties imageProperties() {
        return new AiConversationImageGenerationProperties(
                true,
                "/v1/images/generations",
                "/v1/images/edits",
                1024 * 1024,
                16 * 1024 * 1024L);
    }

    private static AiConversationStreamingRequest request() {
        AiConversationPromptSnapshot prompt = new AiConversationPromptSnapshot(
                "system",
                null,
                null,
                java.util.List.of(),
                new AiConversationContent("draw", java.util.List.of()),
                "generation",
                10,
                false);
        AiConversationImageGenerationOptions options =
                new AiConversationImageGenerationOptions(
                        "image-v2",
                        AiConversationImageAspect.SQUARE,
                        AiConversationImageQuality.HIGH,
                        1024,
                        1024,
                        AiConversationReasoningEffort.HIGH,
                        "webp",
                        90,
                        3,
                        AiConversationImageAction.GENERATE,
                        (short) 1);
        return new AiConversationStreamingRequest(
                new AiConversationModelRequest(
                        "gpt-image-test",
                        128,
                        AiConversationReasoningEffort.HIGH,
                        prompt,
                        options,
                        (short) 0,
                        java.util.List.of()),
                AiConversationWebSearchMode.OFF,
                new AiConversationStreamingDiagnosticContext(
                        new AiConversationStreamTimingContext(
                                "trace",
                                "usage",
                                "conversation",
                                "model",
                                AiConversationStreamTimingPath
                                        .ASYNC_GENERATION_WORKER,
                                1L),
                        "generation"));
    }

    private record DiagnosticRecord(
            String event,
            java.util.Map<String, ?> details) {

        private String checkpoint() {
            if ("ai_image_stream_summary".equals(event)) {
                return "SUMMARY";
            }
            return String.valueOf(details.get("checkpoint"));
        }
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("ai-temperate-service"))
                    && Files.isDirectory(current.resolve("sql"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
