package com.example.temperate.service.user.aiinference.upstream.impl;

import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamException;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamHeaders;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamRequest;
import com.example.temperate.service.user.aiinference.upstream.OpenAiUpstreamErrorDecoder;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

/**
 * 该实现是来限制 OpenAI 错误透传的 Content-Type、包络结构与内部地址标记，避免把 8317 诊断信息误公开给客户端。
 */
@Service
public final class OpenAiUpstreamErrorDecoderImpl
        implements OpenAiUpstreamErrorDecoder {

    private static final Set<String> INTERNAL_MARKERS = Set.of(
            "127.0.0.1:8317", "localhost:8317", "host.docker.internal:8317",
            "cli_proxy_api_key", "authorization: bearer");

    @Override
    public Mono<? extends Throwable> decode(
            ClientResponse response,
            ApiInferenceUpstreamRequest request) {
        int status = response.statusCode().value();
        if (status < 400 || status > 599
                || request == null || !request.allowOpenAiErrorPassThrough()
                || !jsonContentType(response)) {
            return response.releaseBody().thenReturn(controlled(status));
        }
        return response.bodyToMono(JsonNode.class)
                .map(body -> safeEnvelope(body)
                        ? new ApiInferenceUpstreamException(
                                status,
                                (ObjectNode) body,
                                ApiInferenceUpstreamHeaders.from(response.headers().asHttpHeaders()))
                        : controlled(status))
                .switchIfEmpty(Mono.fromSupplier(() -> controlled(status)))
                .onErrorReturn(controlled(status));
    }

    private static boolean jsonContentType(ClientResponse response) {
        MediaType contentType = response.headers().contentType().orElse(null);
        return contentType != null && MediaType.APPLICATION_JSON.isCompatibleWith(contentType);
    }

    private static boolean safeEnvelope(JsonNode body) {
        if (!(body instanceof ObjectNode envelope)
                || !(envelope.get("error") instanceof ObjectNode error)
                || !text(error.get("message"))
                || !text(error.get("type"))
                || !nullableScalar(error.get("param"))
                || !nullableScalar(error.get("code"))) {
            return false;
        }
        String serialized = body.toString().toLowerCase(Locale.ROOT);
        if (serialized.length() > 65_536) {
            return false;
        }
        return INTERNAL_MARKERS.stream().noneMatch(serialized::contains);
    }

    private static boolean text(JsonNode node) {
        return node != null && node.isTextual() && !node.textValue().isBlank();
    }

    private static boolean nullableScalar(JsonNode node) {
        return node == null || node.isNull() || node.isTextual() || node.isNumber();
    }

    private static ApiChatException controlled(int status) {
        ApiChatErrorCode code = status >= 500
                ? ApiChatErrorCode.UPSTREAM_UNAVAILABLE
                : ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR;
        return new ApiChatException(
                code,
                status >= 500
                        ? "The model upstream is unavailable."
                        : "The model upstream rejected the validated request.",
                null);
    }
}
