package com.example.temperate.service.user.aiconversation.model.stream.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentService;
import com.example.temperate.service.user.aiconversation.config.AiConversationWebSearchProperties;
import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.diagnostic.AiUpstreamErrorDiagnostic;
import com.example.temperate.service.user.aiconversation.diagnostic.impl.AiConversationStreamFailureClassifierImpl;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.exception.AiConversationStreamFailureReason;
import com.example.temperate.service.user.aiconversation.exception.AiUpstreamHttpStatusException;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelRequest;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 验证 Responses 联网请求在非成功状态下保留脱敏诊断，同时维持原有用户错误契约。
 */
final class ResponsesWebSearchStreamingStrategyTest {

    @Test
    void preservesSanitized422DiagnosticBehindThePublicFailure() {
        String providerMessage = "Extra inputs are not permitted";
        ResponsesWebSearchStreamingStrategy strategy = strategy(
                "application/json",
                """
                        {"detail":[{"type":"extra_forbidden",
                        "loc":["body","tools",0,"search_context_size"],
                        "msg":"Extra inputs are not permitted"}]}
                        """);

        StepVerifier.create(strategy.stream(request()))
                .expectErrorSatisfies(failure -> {
                    assertThat(failure).isInstanceOf(AiConversationException.class);
                    AiConversationException publicFailure =
                            (AiConversationException) failure;
                    assertThat(publicFailure.getMessage())
                            .isEqualTo("模型联网响应未能完成")
                            .doesNotContain(providerMessage);
                    assertThat(publicFailure.reason()).isEqualTo(
                            AiConversationStreamFailureReason.UNKNOWN_STREAM_FAILURE);
                    assertThat(publicFailure.getCause())
                            .isInstanceOf(AiUpstreamHttpStatusException.class);
                    AiUpstreamHttpStatusException upstream =
                            (AiUpstreamHttpStatusException) publicFailure.getCause();
                    assertThat(upstream.getStatusCode().value()).isEqualTo(422);
                    assertThat(upstream.getMessage())
                            .isEqualTo("AI upstream rejected the request")
                            .doesNotContain(providerMessage);
                    assertThat(upstream.diagnostic().providerParam())
                            .isEqualTo("body.tools.0.search_context_size");
                    assertThat(upstream.diagnostic().sanitizedMessage())
                            .isEqualTo(providerMessage);
                })
                .verify();
    }

    @Test
    void keeps422StatusWhenDiagnosticHeadersCannotBeParsed() {
        ResponsesWebSearchStreamingStrategy strategy = strategy(
                "not a valid media type ???",
                "{\"message\":\"must not replace the status\"}");

        StepVerifier.create(strategy.stream(request()))
                .expectErrorSatisfies(failure -> {
                    assertThat(failure).isInstanceOf(AiConversationException.class);
                    assertThat(failure.getCause())
                            .isInstanceOf(AiUpstreamHttpStatusException.class);
                    AiUpstreamHttpStatusException upstream =
                            (AiUpstreamHttpStatusException) failure.getCause();
                    assertThat(upstream.getStatusCode().value()).isEqualTo(422);
                    assertThat(upstream.diagnostic().contentType())
                            .isEqualTo(AiUpstreamErrorDiagnostic.UNAVAILABLE);
                    assertThat(upstream.diagnostic().sanitizedMessage())
                            .isEqualTo("must not replace the status");
                })
                .verify();
    }

    private static ResponsesWebSearchStreamingStrategy strategy(
            String contentType,
            String body) {
        WebClient.Builder clientBuilder = WebClient.builder()
                .exchangeFunction(ignored -> Mono.just(ClientResponse.create(
                                HttpStatus.UNPROCESSABLE_ENTITY)
                        .header(HttpHeaders.CONTENT_TYPE, contentType)
                        .body(body)
                        .build()));
        return new ResponsesWebSearchStreamingStrategy(
                clientBuilder,
                new AiInferenceProperties(
                        true,
                        "http://cli-proxy.test",
                        "test-only-key",
                        Duration.ofSeconds(5)),
                new AiConversationWebSearchProperties(
                        true, "/v1/responses"),
                mock(AiConversationAttachmentService.class),
                new ObjectMapper(),
                new AiConversationStreamFailureClassifierImpl());
    }

    private static AiConversationStreamingRequest request() {
        AiConversationPromptSnapshot prompt = new AiConversationPromptSnapshot(
                "system",
                null,
                null,
                List.of(),
                new AiConversationContent("hello", List.of()),
                "generation",
                10,
                false);
        return new AiConversationStreamingRequest(
                new AiConversationModelRequest(
                        "grok-4.3",
                        128,
                        AiConversationReasoningEffort.HIGH,
                        prompt),
                AiConversationWebSearchMode.AUTO);
    }
}
