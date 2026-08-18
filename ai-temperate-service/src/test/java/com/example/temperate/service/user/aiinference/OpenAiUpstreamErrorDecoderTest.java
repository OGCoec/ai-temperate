package com.example.temperate.service.user.aiinference;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamException;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamRequest;
import com.example.temperate.service.user.aiinference.upstream.impl.OpenAiUpstreamErrorDecoderImpl;
import com.example.temperate.service.user.apichat.ApiChatException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;

/**
 * 该测试是来约束上游 OpenAI 错误只有在包络、Content-Type 和内部信息检查全部通过后才能保留原状态与安全头。
 */
final class OpenAiUpstreamErrorDecoderTest {

    private final OpenAiUpstreamErrorDecoderImpl decoder =
            new OpenAiUpstreamErrorDecoderImpl();

    @Test
    void preservesSafeOpenAiErrorAndOnlyAllowlistedHeaders() {
        ClientResponse response = ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("x-request-id", "req_7")
                .header("x-ratelimit-remaining-tokens", "0")
                .header("Set-Cookie", "secret=value")
                .body("""
                        {"error":{"message":"Rate limit exceeded.",
                        "type":"rate_limit_error","param":null,
                        "code":"rate_limit_exceeded"}}
                        """)
                .build();

        Throwable decoded = decoder.decode(
                response, new ApiInferenceUpstreamRequest("client-7", true)).block();

        assertThat(decoded).isInstanceOf(ApiInferenceUpstreamException.class);
        ApiInferenceUpstreamException safe = (ApiInferenceUpstreamException) decoded;
        assertThat(safe.status()).isEqualTo(429);
        assertThat(safe.envelope().at("/error/code").textValue())
                .isEqualTo("rate_limit_exceeded");
        assertThat(safe.headers().values()).containsKey("x-request-id");
        assertThat(safe.headers().values()).doesNotContainKey("set-cookie");
    }

    @Test
    void hidesErrorsForLegacyProvidersOrBodiesContainingInternalRoutes() {
        ClientResponse legacy = response("Provider rejected the request.");
        ClientResponse internal = response("Failed to call 127.0.0.1:8317.");

        assertThat(decoder.decode(
                legacy, new ApiInferenceUpstreamRequest(null, false)).block())
                .isInstanceOf(ApiChatException.class);
        assertThat(decoder.decode(
                internal, new ApiInferenceUpstreamRequest(null, true)).block())
                .isInstanceOf(ApiChatException.class);
    }

    private static ClientResponse response(String message) {
        return ClientResponse.create(HttpStatus.BAD_GATEWAY)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"error\":{\"message\":\"" + message
                        + "\",\"type\":\"server_error\",\"param\":null,"
                        + "\"code\":\"upstream_error\"}}")
                .build();
    }
}
