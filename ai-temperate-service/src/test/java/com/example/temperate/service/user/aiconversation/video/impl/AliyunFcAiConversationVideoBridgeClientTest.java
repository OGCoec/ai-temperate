package com.example.temperate.service.user.aiconversation.video.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.temperate.service.user.aiconversation.config.AiConversationVideoGenerationProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 验证 FC 进度流即使在网络分块把一条 NDJSON 拆开时，主服务仍按完整行消费，而不会把半个 JSON 误当成事件。
 */
final class AliyunFcAiConversationVideoBridgeClientTest {

    @Test
    void joinsNetworkChunksBeforeEmittingNdjsonFrame() {
        DefaultDataBufferFactory buffers = new DefaultDataBufferFactory();

        List<String> frames = AliyunFcAiConversationVideoBridgeClient.splitNdjson(
                        Flux.just(
                                buffers.wrap("{\"type\":\"pro".getBytes(StandardCharsets.UTF_8)),
                                buffers.wrap("gress\",\"sequence\":1}\n{\"type\":\"completed\"".getBytes(StandardCharsets.UTF_8)),
                                buffers.wrap(",\"sequence\":2}\n".getBytes(StandardCharsets.UTF_8))))
                .collectList()
                .block();

        assertEquals(List.of(
                "{\"type\":\"progress\",\"sequence\":1}",
                "{\"type\":\"completed\",\"sequence\":2}"), frames);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SOURCE_OPEN_FAILED",
            "OSS_CREDENTIALS_UNAVAILABLE",
            "OSS_MULTIPART_INIT_FAILED",
            "OSS_PART_UPLOAD_FAILED",
            "OSS_COMPLETE_FAILED",
            "OSS_HEAD_VERIFY_FAILED",
            "OSS_TRANSFER_FAILED"
    })
    void preservesWhitelistedFailureCodeFromNdjsonFrame(String errorCode) {
        AliyunFcAiConversationVideoBridgeClient client = failedClient(errorCode);

        AliyunFcVideoTransferFailureException failure = assertThrows(
                AliyunFcVideoTransferFailureException.class,
                () -> client.invokeTransfer(
                        new Object(), JsonNode.class, ignored -> { }));

        assertEquals(errorCode, failure.errorCode());
    }

    @Test
    void downgradesUnknownFailureCodeBeforeLeavingFcBoundary() {
        AliyunFcAiConversationVideoBridgeClient client = failedClient(
                "UNTRUSTED_REMOTE_EXCEPTION");

        AliyunFcVideoTransferFailureException failure = assertThrows(
                AliyunFcVideoTransferFailureException.class,
                () -> client.invokeTransfer(
                        new Object(), JsonNode.class, ignored -> { }));

        assertEquals("OSS_TRANSFER_FAILED", failure.errorCode());
    }

    private static AliyunFcAiConversationVideoBridgeClient failedClient(
            String errorCode) {
        String responseBody = """
                {"schemaVersion":1,"type":"failed","sequence":1,"errorCode":"%s"}
                """.formatted(errorCode);
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(ignored -> Mono.just(ClientResponse
                        .create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE,
                                "application/x-ndjson")
                        .body(responseBody)
                        .build()));
        return new AliyunFcAiConversationVideoBridgeClient(
                builder,
                new ObjectMapper(),
                videoProperties(),
                Clock.systemUTC());
    }

    private static AiConversationVideoGenerationProperties videoProperties() {
        AiConversationVideoGenerationProperties defaults =
                AiConversationVideoGenerationProperties.officialDefaults();
        return new AiConversationVideoGenerationProperties(
                false,
                defaults.pollInterval(),
                defaults.maximumPollingDuration(),
                defaults.maximumResponseJsonBytes(),
                defaults.endpoints(),
                defaults.version15(),
                defaults.legacy(),
                new AiConversationVideoGenerationProperties.FunctionCompute(
                        "https://fc.example.test/invoke",
                        "test-hmac-secret-with-at-least-32-bytes",
                        Duration.ofSeconds(5),
                        "ai/video/",
                        2_147_483_648L,
                        List.of("vidgen.x.ai", "api.x.ai")));
    }
}
