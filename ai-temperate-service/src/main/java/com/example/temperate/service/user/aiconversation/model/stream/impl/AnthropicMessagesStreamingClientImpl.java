package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.config.AiConversationAnthropicProperties;
import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.exception.AiConversationStreamFailureReason;
import com.example.temperate.service.user.aiconversation.exception.AiUpstreamHttpStatusException;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.example.temperate.service.user.aiconversation.model.stream.AnthropicMessagesStreamingClient;
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
 * 通过共享 CLIProxyAPI 主机调用 Anthropic Messages，并在固定内存边界内解析 SSE 和非成功响应。
 */
@Service
public final class AnthropicMessagesStreamingClientImpl
        implements AnthropicMessagesStreamingClient {

    private final WebClient.Builder webClientBuilder;
    private final AiInferenceProperties inferenceProperties;
    private final AiConversationAnthropicProperties anthropicProperties;
    private final ObjectMapper objectMapper;
    private final AiUpstreamErrorCapture upstreamErrorCapture;

    public AnthropicMessagesStreamingClientImpl(
            WebClient.Builder webClientBuilder,
            AiInferenceProperties inferenceProperties,
            AiConversationAnthropicProperties anthropicProperties,
            ObjectMapper objectMapper) {
        this.webClientBuilder = Objects.requireNonNull(webClientBuilder);
        this.inferenceProperties = Objects.requireNonNull(inferenceProperties);
        this.anthropicProperties = Objects.requireNonNull(anthropicProperties);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.upstreamErrorCapture = new AiUpstreamErrorCapture(objectMapper);
    }

    @Override
    public Flux<AiConversationModelEvent> stream(JsonNode requestBody) {
        WebClient client = webClientBuilder.clone()
                .baseUrl(inferenceProperties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "Bearer " + inferenceProperties.apiKey())
                .defaultHeader("anthropic-version", anthropicProperties.apiVersion())
                .build();
        return client.post()
                .uri(anthropicProperties.messagesPath())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(requestBody)
                .exchangeToFlux(this::decodeResponse)
                .flatMapSequential(
                        AnthropicMessagesStreamingClientImpl::rejectFailure,
                        1,
                        1)
                .transform(upstream -> enforceTotalDeadline(upstream));
    }

    private Flux<AiConversationModelEvent> decodeResponse(ClientResponse response) {
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
            OpenAiResponsesSseDecoder decoder = new OpenAiResponsesSseDecoder();
            AnthropicMessagesEventMapper mapper =
                    new AnthropicMessagesEventMapper(objectMapper);
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
                "Anthropic 模型响应未能完成",
                true,
                AiConversationStreamFailureReason.UPSTREAM_PROTOCOL_ERROR,
                new IllegalStateException("Anthropic SSE protocol failure"));
    }

    private <T> Flux<T> enforceTotalDeadline(Flux<T> upstream) {
        Mono<Void> deadline = Mono.delay(inferenceProperties.maxStreamDuration())
                .then(Mono.error(new TimeoutException(
                        "Anthropic maximum stream duration exceeded")));
        return upstream.takeUntilOther(deadline);
    }
}
