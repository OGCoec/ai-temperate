package com.example.temperate.service.user.apichat.upstream.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamRequest;
import com.example.temperate.service.user.aiinference.upstream.impl.OpenAiUpstreamErrorDecoderImpl;
import com.example.temperate.service.user.apichat.upstream.ApiChatUpstreamStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * 该契约测试是来由进程内假 8317 捕获 WebClient 实际发送的路径、服务端 Bearer 和 JSON 类型，防止客户端 Key 透传或可预防的 422。
 */
final class WebClientApiChatUpstreamClientContractTest {

    @Test
    void sendsExactChatPathServerCredentialAndTypedJson() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        DisposableServer server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> request.receive()
                        .aggregate()
                        .asString(StandardCharsets.UTF_8)
                        .flatMap(received -> {
                            body.set(received);
                            authorization.set(request.requestHeaders().get(
                                    HttpHeaders.AUTHORIZATION));
                            path.set(request.uri());
                            return response.status(200)
                                    .header(
                                            HttpHeaders.CONTENT_TYPE,
                                            MediaType.TEXT_EVENT_STREAM_VALUE)
                                    .sendString(Mono.just("data: [DONE]\n\n"))
                                    .then();
                        }))
                .bindNow();
        try {
            String upstreamKey = "server-only-test-key";
            WebClient webClient = WebClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.port())
                    .defaultHeaders(headers -> headers.setBearerAuth(upstreamKey))
                    .build();
            WebClientApiChatUpstreamClient client =
                    new WebClientApiChatUpstreamClient(
                            webClient,
                            new AiInferenceProperties(
                                    true,
                                    "http://127.0.0.1:" + server.port(),
                                    upstreamKey,
                                    Duration.ofSeconds(5)),
                            new SimpleMeterRegistry(),
                            new OpenAiUpstreamErrorDecoderImpl());
            ObjectMapper objectMapper = new ObjectMapper();
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", "gpt-test");
            payload.putArray("messages")
                    .addObject()
                    .put("role", "user")
                    .put("content", "hello");
            payload.put("stream", true);
            payload.put("max_completion_tokens", 128);
            payload.put("temperature", 0.5D);

            assertThat(client.stream(
                            payload,
                            new ApiInferenceUpstreamRequest(null, false))
                    .flatMapMany(ApiChatUpstreamStream::body)
                    .blockFirst(Duration.ofSeconds(5)))
                    .isEqualTo("[DONE]");

            JsonNode captured = objectMapper.readTree(body.get());
            assertThat(path.get()).isEqualTo("/v1/chat/completions");
            assertThat(authorization.get()).isEqualTo("Bearer " + upstreamKey);
            assertThat(captured.get("stream").isBoolean()).isTrue();
            assertThat(captured.get("max_completion_tokens").isIntegralNumber())
                    .isTrue();
            assertThat(captured.get("temperature").isNumber()).isTrue();
            assertThat(captured.get("messages").isArray()).isTrue();
        } finally {
            server.disposeNow();
        }
    }
}
