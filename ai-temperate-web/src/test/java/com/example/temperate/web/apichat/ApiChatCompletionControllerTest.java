package com.example.temperate.web.apichat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamHeaders;
import com.example.temperate.service.user.apichat.ApiChatCompletionCreation;
import com.example.temperate.service.user.apichat.ApiChatCompletionService;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 该测试是来约束 Chat Controller 依据服务结果选择 JSON 或 SSE，并只公开白名单上游头与合法客户端请求 ID。
 */
final class ApiChatCompletionControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsNonStreamingJsonWithoutSseBufferingHeader() {
        ObjectNode body = objectMapper.createObjectNode()
                .put("object", "chat.completion");
        ApiChatCompletionService service = (principal, request, clientRequestId) ->
                new ApiChatCompletionCreation.Json(Mono.just(
                        new ApiChatCompletionCreation.HttpJson(
                                body,
                                new ApiInferenceUpstreamHeaders(Map.of(
                                        "x-request-id", List.of("req_safe"))))));
        ApiChatCompletionController controller =
                new ApiChatCompletionController(service);

        var response = controller.create(principal(), "client-7", request(false)).block();

        assertThat(response).isNotNull();
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders().getFirst("x-request-id"))
                .isEqualTo("req_safe");
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isNull();
        assertThat(response.getBody()).isSameAs(body);
    }

    @Test
    void returnsStreamingSseAndForwardsValidatedClientRequestId() {
        AtomicReference<String> forwarded = new AtomicReference<>();
        ApiChatCompletionService service = (principal, request, clientRequestId) -> {
            forwarded.set(clientRequestId);
            return new ApiChatCompletionCreation.Stream(Mono.just(
                    new ApiChatCompletionCreation.HttpStream(
                            Flux.just("{\"choices\":[]}", "[DONE]"),
                            ApiInferenceUpstreamHeaders.empty())));
        };
        ApiChatCompletionController controller =
                new ApiChatCompletionController(service);

        var response = controller.create(principal(), "client-8", request(true)).block();
        @SuppressWarnings("unchecked")
        Flux<ServerSentEvent<String>> stream =
                (Flux<ServerSentEvent<String>>) response.getBody();

        assertThat(forwarded).hasValue("client-8");
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
        assertThat(stream.map(ServerSentEvent::data).collectList().block())
                .containsExactly("{\"choices\":[]}", "[DONE]");
    }

    @Test
    void rejectsNonAsciiClientRequestIdBeforeCallingService() {
        ApiChatCompletionService service = (principal, request, clientRequestId) -> {
            throw new AssertionError("The service must not receive an invalid header.");
        };
        ApiChatCompletionController controller =
                new ApiChatCompletionController(service);

        assertThatThrownBy(() -> controller.create(
                principal(), "请求-7", request(false)))
                .isInstanceOf(com.example.temperate.service.user.apichat
                        .ApiChatException.class)
                .hasMessageContaining("printable ASCII");
    }

    private ObjectNode request(boolean stream) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "gpt-test");
        request.putArray("messages")
                .addObject()
                .put("role", "user")
                .put("content", "hello");
        request.put("stream", stream);
        return request;
    }

    private static ApiKeyPrincipal principal() {
        return new ApiKeyPrincipal(
                new byte[16], 17L, new byte[32], "B".repeat(43), Set.of(7L));
    }
}
