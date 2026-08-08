package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.config.AiConversationGeminiProperties;
import com.example.temperate.service.user.aiconversation.config.AiConversationImageGenerationProperties;
import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.exception.AiConversationStreamFailureReason;
import com.example.temperate.service.user.aiconversation.exception.AiUpstreamHttpStatusException;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.example.temperate.service.user.aiconversation.model.stream.GeminiInteractionsStreamingClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 通过共享 CLIProxyAPI 主机调用 Google Interactions，并为图片事件使用独立的大事件内存上限和严格聚合。
 */
@Service
public final class GeminiInteractionsStreamingClientImpl
        implements GeminiInteractionsStreamingClient {

    private final WebClient.Builder webClientBuilder;
    private final AiInferenceProperties inferenceProperties;
    private final AiConversationGeminiProperties geminiProperties;
    private final AiConversationImageGenerationProperties imageProperties;
    private final ObjectMapper objectMapper;
    private final AiUpstreamErrorCapture upstreamErrorCapture;

    public GeminiInteractionsStreamingClientImpl(
            WebClient.Builder webClientBuilder,
            AiInferenceProperties inferenceProperties,
            AiConversationGeminiProperties geminiProperties,
            AiConversationImageGenerationProperties imageProperties,
            ObjectMapper objectMapper) {
        this.webClientBuilder = Objects.requireNonNull(webClientBuilder);
        this.inferenceProperties = Objects.requireNonNull(inferenceProperties);
        this.geminiProperties = Objects.requireNonNull(geminiProperties);
        this.imageProperties = Objects.requireNonNull(imageProperties);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.upstreamErrorCapture = new AiUpstreamErrorCapture(objectMapper);
    }

    @Override
    public Flux<AiConversationModelEvent> stream(
            JsonNode requestBody,
            AiConversationImageGenerationOptions imageOptions,
            short outputIndex) {
        WebClient client = webClientBuilder.clone()
                .baseUrl(inferenceProperties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "Bearer " + inferenceProperties.apiKey())
                .defaultHeader("Api-Revision", geminiProperties.apiRevision())
                .build();
        return client.post()
                .uri(geminiProperties.interactionsPath())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(requestBody)
                .exchangeToFlux(response -> decodeResponse(
                        response, imageOptions, outputIndex))
                .flatMapSequential(
                        GeminiInteractionsStreamingClientImpl::rejectFailure,
                        1,
                        1)
                .transform(upstream -> enforceTotalDeadline(upstream));
    }

    private Flux<AiConversationModelEvent> decodeResponse(
            ClientResponse response,
            AiConversationImageGenerationOptions imageOptions,
            short outputIndex) {
        if (!response.statusCode().is2xxSuccessful()) {
            return upstreamErrorCapture.capture(response)
                    .flatMapMany(diagnostic -> Flux.error(
                            new AiUpstreamHttpStatusException(
                                    response.statusCode(), diagnostic)));
        }
        if (response.headers().contentType()
                .filter(MediaType.TEXT_EVENT_STREAM::isCompatibleWith)
                .isEmpty()) {
            return response.releaseBody().thenMany(Flux.error(protocolFailure()));
        }
        return Flux.defer(() -> {
            int maximumCharacters = imageOptions == null
                    ? 2_097_152 : imageProperties.maximumSseCharacters();
            OpenAiResponsesSseDecoder decoder = new OpenAiResponsesSseDecoder(
                    maximumCharacters, maximumCharacters);
            GeminiInteractionsEventMapper mapper = new GeminiInteractionsEventMapper(
                    objectMapper,
                    imageOptions,
                    outputIndex,
                    imageProperties.maximumDecodedImageBytes());
            return response.bodyToFlux(DataBuffer.class)
                    .concatMapIterable(buffer -> decodeBuffer(decoder, buffer))
                    .concatWith(Flux.defer(() -> Flux.fromIterable(decoder.finish())))
                    .concatMapIterable(mapper::map);
        });
    }

    private static List<OpenAiResponsesSseEvent> decodeBuffer(
            OpenAiResponsesSseDecoder decoder, DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return decoder.accept(bytes, 0, bytes.length);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private static Mono<AiConversationModelEvent> rejectFailure(
            AiConversationModelEvent event) {
        return event instanceof AiConversationModelEvent.Failure
                ? Mono.error(protocolFailure()) : Mono.just(event);
    }

    private static AiConversationException protocolFailure() {
        return new AiConversationException(
                AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED,
                "Google 模型响应未能完成",
                true,
                AiConversationStreamFailureReason.UPSTREAM_PROTOCOL_ERROR,
                new IllegalStateException("Google Interactions protocol failure"));
    }

    private <T> Flux<T> enforceTotalDeadline(Flux<T> upstream) {
        Mono<Void> deadline = Mono.delay(inferenceProperties.maxStreamDuration())
                .then(Mono.error(new TimeoutException(
                        "Google maximum stream duration exceeded")));
        return upstream.takeUntilOther(deadline);
    }
}
