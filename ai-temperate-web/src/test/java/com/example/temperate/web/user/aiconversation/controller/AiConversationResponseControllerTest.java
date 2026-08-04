package com.example.temperate.web.user.aiconversation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.response.AiConversationDirectResponseCancellationService;
import com.example.temperate.service.user.aiconversation.response.AiConversationDirectResponseCancellationStatus;
import com.example.temperate.service.user.aiconversation.response.AiConversationAcceptedData;
import com.example.temperate.service.user.aiconversation.response.AiConversationCompletedData;
import com.example.temperate.service.user.aiconversation.response.AiConversationResponseCommand;
import com.example.temperate.service.user.aiconversation.response.AiConversationResponseService;
import com.example.temperate.service.user.aiconversation.response.AiConversationResponseStream;
import com.example.temperate.service.user.aiconversation.response.AiConversationStreamEvent;
import com.example.temperate.web.aiconversation.AiConversationPublicId;
import com.example.temperate.web.user.aiconversation.api.AiConversationExceptionHandler;
import com.example.temperate.web.user.aiconversation.api.AiConversationInputRequest;
import com.example.temperate.web.user.aiconversation.api.AiConversationImageRequest;
import com.example.temperate.web.user.aiconversation.api.AiConversationResponseRequest;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * 验证 AI 会话 Controller 的 SSE 成功协议与建流前 JSON 错误协商边界。
 */
final class AiConversationResponseControllerTest {

    private static final SessionPrincipal PRINCIPAL =
            new SessionPrincipal(7L, "public-user", "Alice");
    private static final String REQUEST_BODY = """
            {
              "modelPublicId": "AAAAAAAAAAA",
              "reasoningEffortLevel": 2,
              "input": {
                "text": "hello",
                "attachments": []
              }
            }
            """;

    @Test
    void rejectsNonV4IdempotencyKeyBeforeCallingService() {
        AiConversationResponseService service =
                mock(AiConversationResponseService.class);
        AiConversationResponseController controller =
                new AiConversationResponseController(service);

        assertThatThrownBy(() -> controller.createAndRespond(
                new SessionPrincipal(7L, "public-user", "Alice"),
                "019fade7-eae9-72c3-9208-057c793971a7",
                new AiConversationResponseRequest(
                        "AAAAAAAAAAA",
                        (short) 2,
                        null,
                        new AiConversationInputRequest("hello", List.of()))))
                .isInstanceOf(AiConversationException.class)
                .hasMessage("Idempotency-Key must be a UUIDv4.");
    }

    @Test
    void createReturnsNoStoreUnbufferedNamedSseEvents() {
        AiConversationResponseService service =
                mock(AiConversationResponseService.class);
        AiConversationStreamEvent accepted = AiConversationStreamEvent.accepted(
                new AiConversationAcceptedData(
                        "AAAAAAAAAAAAAAAAAAAAAA",
                        "BBBBBBBBBBBBBBBBBBBBBB",
                        "AAAAAAAAAAA",
                        true));
        AiConversationStreamEvent completed =
                AiConversationStreamEvent.completed(
                        new AiConversationCompletedData(
                                "AAAAAAAAAAAAAAAAAAAAAA",
                                "AAAAAAAAAAA",
                                "BBBBBBBBBBBBBBBBBBBBBB",
                                "10",
                                "0",
                                "5",
                                "0",
                                "1",
                                "STOP"));
        when(service.respond(any())).thenReturn(
                new AiConversationResponseStream(
                        accepted, Flux.just(completed)));
        AiConversationResponseController controller =
                new AiConversationResponseController(service);

        ResponseEntity<Flux<ServerSentEvent<Object>>> response =
                controller.createAndRespond(
                        new SessionPrincipal(7L, "public-user", "Alice"),
                        "4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6",
                        new AiConversationResponseRequest(
                                "AAAAAAAAAAA",
                                (short) 4,
                                null,
                                new AiConversationInputRequest(
                                        "hello", List.of())));

        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .contains("no-store")
                .contains("no-transform");
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering"))
                .isEqualTo("no");
        StepVerifier.create(response.getBody())
                .assertNext(event -> assertThat(event.event())
                        .isEqualTo("accepted"))
                .assertNext(event -> assertThat(event.event())
                        .isEqualTo("completed"))
                .verifyComplete();
        ArgumentCaptor<AiConversationResponseCommand> commandCaptor =
                ArgumentCaptor.forClass(AiConversationResponseCommand.class);
        verify(service).respond(commandCaptor.capture());
        assertThat(commandCaptor.getValue().reasoningEffort())
                .isEqualTo(AiConversationReasoningEffort.EXTRA_HIGH);
    }

    @Test
    void missingReasoningEffortDefaultsToMedium() {
        AiConversationResponseService service =
                mock(AiConversationResponseService.class);
        AiConversationStreamEvent accepted = AiConversationStreamEvent.accepted(
                new AiConversationAcceptedData(
                        "AAAAAAAAAAAAAAAAAAAAAA",
                        "BBBBBBBBBBBBBBBBBBBBBB",
                        "AAAAAAAAAAA",
                        true));
        when(service.respond(any())).thenReturn(
                new AiConversationResponseStream(accepted, Flux.empty()));
        AiConversationResponseController controller =
                new AiConversationResponseController(service);

        controller.createAndRespond(
                new SessionPrincipal(7L, "public-user", "Alice"),
                "4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6",
                new AiConversationResponseRequest(
                        "AAAAAAAAAAA",
                        null,
                        null,
                        new AiConversationInputRequest("hello", List.of())));

        ArgumentCaptor<AiConversationResponseCommand> commandCaptor =
                ArgumentCaptor.forClass(AiConversationResponseCommand.class);
        verify(service).respond(commandCaptor.capture());
        assertThat(commandCaptor.getValue().reasoningEffort())
                .isEqualTo(AiConversationReasoningEffort.MEDIUM);
        assertThat(commandCaptor.getValue().webSearchMode())
                .isEqualTo(
                        com.example.temperate.service.user.aiconversation.response
                                .AiConversationWebSearchMode.OFF);
    }

    @Test
    void rejectsImageGenerationWhenAsyncGenerationIsDisabled() {
        AiConversationResponseService service =
                mock(AiConversationResponseService.class);
        AiConversationResponseController controller =
                new AiConversationResponseController(service);

        assertThatThrownBy(() -> controller.createAndRespond(
                        PRINCIPAL,
                        "4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6",
                        new AiConversationResponseRequest(
                                "AAAAAAAAAAA",
                                (short) 5,
                                null,
                                new AiConversationImageRequest(
                                        AiConversationImageAspect.PORTRAIT),
                                new AiConversationInputRequest(
                                        "draw a fox", List.of()))))
                .isInstanceOf(AiConversationException.class)
                .hasMessageContaining("异步 Generation");
        verifyNoInteractions(service);
    }

    @Test
    void routesImageRequestWithWebSearchToGenerationValidation() {
        AiConversationResponseService responseService =
                mock(AiConversationResponseService.class);
        var generationService = mock(
                com.example.temperate.service.user.aiconversation.generation
                        .AiConversationGenerationService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.example.temperate.service.user.aiconversation.generation
                .AiConversationGenerationService> generationProvider =
                mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.example.temperate.service.user.aiconversation.generation.observer
                .AiConversationGenerationObserverService> observerProvider =
                mock(ObjectProvider.class);
        var observerService = mock(
                com.example.temperate.service.user.aiconversation.generation.observer
                        .AiConversationGenerationObserverService.class);
        when(generationProvider.getIfAvailable()).thenReturn(generationService);
        when(observerProvider.getIfAvailable()).thenReturn(observerService);
        when(generationService.create(any())).thenThrow(new AiConversationException(
                AiConversationErrorCode.AI_REQUEST_INVALID,
                "图片生成不支持联网搜索。",
                false));
        AiConversationResponseController controller =
                new AiConversationResponseController(
                        responseService,
                        generationProvider,
                        observerProvider,
                        mock(com.example.temperate.common.codec.id.HybridBase64UrlCodec.class),
                        mock(AiConversationDirectResponseCancellationService.class));

        assertThatThrownBy(() -> controller.createAndRespond(
                PRINCIPAL,
                "4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6",
                new AiConversationResponseRequest(
                        "AAAAAAAAAAA",
                        (short) 2,
                        com.example.temperate.service.user.aiconversation.response
                                .AiConversationWebSearchMode.REQUIRED,
                        new AiConversationImageRequest(
                                AiConversationImageAspect.PORTRAIT),
                        new AiConversationInputRequest(
                                "draw a fox", List.of()))))
                .isInstanceOf(AiConversationException.class)
                .hasMessageContaining("不支持联网搜索");
        verify(generationService).create(any());
        verifyNoInteractions(responseService);
    }

    @Test
    void cancelEndpointUsesOriginalKeyAndReturnsAcceptedForActiveStream() {
        AiConversationResponseService responseService =
                mock(AiConversationResponseService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationService>
                generationProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationObserverService>
                observerProvider = mock(ObjectProvider.class);
        AiConversationDirectResponseCancellationService cancellationService =
                mock(AiConversationDirectResponseCancellationService.class);
        when(cancellationService.requestUserStop(
                eq(7L), any(UUID.class), any(String.class)))
                .thenReturn(AiConversationDirectResponseCancellationStatus.CANCEL_REQUESTED);
        AiConversationResponseController controller =
                new AiConversationResponseController(
                        responseService,
                        generationProvider,
                        observerProvider,
                        mock(com.example.temperate.common.codec.id.HybridBase64UrlCodec.class),
                        cancellationService);

        ResponseEntity<?> response = controller.cancel(
                PRINCIPAL,
                "4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().toString())
                .contains("CANCEL_REQUESTED");
        verify(cancellationService).requestUserStop(
                eq(7L),
                eq(UUID.fromString("4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6")),
                any(String.class));
    }

    @Test
    void bothResponseEndpointsDeclareStreamAndJsonRepresentations()
            throws NoSuchMethodException {
        Method create = AiConversationResponseController.class.getMethod(
                "createAndRespond",
                SessionPrincipal.class,
                String.class,
                AiConversationResponseRequest.class);
        Method continuation = AiConversationResponseController.class.getMethod(
                "continueAndRespond",
                SessionPrincipal.class,
                AiConversationPublicId.class,
                String.class,
                AiConversationResponseRequest.class);

        assertThat(create.getAnnotation(PostMapping.class).produces())
                .containsExactlyInAnyOrder(
                        MediaType.TEXT_EVENT_STREAM_VALUE,
                        MediaType.APPLICATION_JSON_VALUE);
        assertThat(continuation.getAnnotation(PostMapping.class).produces())
                .containsExactlyInAnyOrder(
                        MediaType.TEXT_EVENT_STREAM_VALUE,
                        MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    void quotaFailureReturnsJsonWhenClientAcceptsOnlyEventStream()
            throws Exception {
        assertQuotaFailure(MediaType.TEXT_EVENT_STREAM);
    }

    @Test
    void quotaFailureReturnsJsonWhenClientAcceptsEventStreamAndJson()
            throws Exception {
        assertQuotaFailure(
                MediaType.TEXT_EVENT_STREAM,
                MediaType.APPLICATION_JSON);
    }

    @Test
    void committedSseClientDisconnectIsHandledWithoutJsonBody() {
        AiConversationExceptionHandler exceptionHandler =
                new AiConversationExceptionHandler(Clock.systemUTC());

        exceptionHandler.handleClientDisconnect(
                new AsyncRequestNotUsableException("client disconnected"));
    }

    private static void assertQuotaFailure(MediaType... acceptedTypes)
            throws Exception {
        AiConversationResponseService service =
                mock(AiConversationResponseService.class);
        when(service.respond(any())).thenThrow(new AiConversationException(
                AiConversationErrorCode.AI_QUOTA_INSUFFICIENT,
                "不应向客户端暴露的内部额度诊断",
                false));
        AiConversationExceptionHandler exceptionHandler =
                new AiConversationExceptionHandler(Clock.fixed(
                        Instant.parse("2026-07-31T13:40:01Z"),
                        ZoneOffset.UTC));
        // standalone MockMvc 不加载 Boot 的时间序列化默认值，此处显式复现真实应用的 ISO-8601 HTTP 契约。
        MappingJackson2HttpMessageConverter jsonConverter =
                new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(
                                        SerializationFeature
                                                .WRITE_DATES_AS_TIMESTAMPS)
                                .build());
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new AiConversationResponseController(service))
                .setControllerAdvice(exceptionHandler)
                .setCustomArgumentResolvers(new SessionPrincipalResolver())
                .setMessageConverters(jsonConverter)
                .build();

        mockMvc.perform(post("/api/ai/conversations/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(acceptedTypes)
                        .header(
                                "Idempotency-Key",
                                "4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6")
                        .content(REQUEST_BODY))
                .andExpect(status().isPaymentRequired())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON))
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("private")))
                .andExpect(jsonPath("$.code").value(
                        "AI_QUOTA_INSUFFICIENT"))
                .andExpect(jsonPath("$.message").value(
                        "额度不足，请充值。"))
                .andExpect(jsonPath("$.timestamp").value(
                        "2026-07-31T13:40:01Z"));
    }

    /**
     * 为独立 MockMvc 注入已认证会话主体，避免把本测试扩展为完整 Security 上下文测试。
     */
    private static final class SessionPrincipalResolver
            implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == SessionPrincipal.class;
        }

        @Override
        public Object resolveArgument(
                MethodParameter parameter,
                ModelAndViewContainer mavContainer,
                NativeWebRequest webRequest,
                WebDataBinderFactory binderFactory) {
            return PRINCIPAL;
        }
    }
}
