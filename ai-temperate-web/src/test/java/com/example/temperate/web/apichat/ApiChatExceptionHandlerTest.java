package com.example.temperate.web.apichat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apichat.ApiChatCompletionService;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 该测试是来锁定 Chat 请求失败时固定返回 JSON，避免 Worker 请求 SSE 后 Spring 因错误响应内容协商失败而把客户端 400 伪装成 502。
 */
final class ApiChatExceptionHandlerTest {

    @Test
    void committedChatSseDisconnectDoesNotCreateASecondResponseBody() throws Exception {
        ApiChatExceptionHandler handler = new ApiChatExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.flushBuffer();
        String previousTrace = MDC.get("apiChatTraceId");
        MDC.put("apiChatTraceId", "trace-client-disconnect");

        try (LogCapture logs = LogCapture.start()) {
            Object mapped = handler.ioFailure(
                    new IOException("private-localized-message"), request, response);

            assertThat(mapped).isNull();
            assertThat(logs.joined())
                    .contains("event=api_inference_client_disconnected")
                    .contains("protocol=chat_completions")
                    .contains("outcome=client_disconnected")
                    .doesNotContain("private-localized-message");
            assertThat(logs.hasLevel(Level.WARN)).isFalse();
            assertThat(logs.hasLevel(Level.ERROR)).isFalse();
        } finally {
            if (previousTrace == null) {
                MDC.remove("apiChatTraceId");
            } else {
                MDC.put("apiChatTraceId", previousTrace);
            }
        }
    }

    @Test
    void committedResponsesSseDisconnectUsesTheSameNoBodyTermination() throws Exception {
        ApiChatExceptionHandler handler = new ApiChatExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/responses");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.flushBuffer();

        assertThat(handler.ioFailure(
                new IOException("private-message"), request, response)).isNull();
    }

    @Test
    void committedNonSseIoFailureAlsoRefusesASecondResponseBody() throws Exception {
        ApiChatExceptionHandler handler = new ApiChatExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.flushBuffer();

        assertThat(handler.ioFailure(
                new IOException("private-message"), request, response)).isNull();
    }

    @Test
    void uncommittedIoFailureReturnsOpenAiUpstreamUnavailableJson() {
        ApiChatExceptionHandler handler = new ApiChatExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Object mapped = handler.ioFailure(
                new IOException("private-message"), request, response);

        assertThat(mapped).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> entity = (ResponseEntity<?>) mapped;
        assertThat(entity.getStatusCode().value()).isEqualTo(503);
        assertThat(entity.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(entity.getBody()).isInstanceOf(ApiChatErrorResponse.class);
        ApiChatErrorResponse body = (ApiChatErrorResponse) entity.getBody();
        assertThat(body.error().code()).isEqualTo("upstream_unavailable");
        assertThat(body.error().type()).isEqualTo("server_error");
    }

    @Test
    void invalidRequestUsesExplicitJsonContentType() {
        ApiChatExceptionHandler handler = new ApiChatExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/chat/completions");
        request.addHeader(HttpHeaders.ACCEPT,
                "text/event-stream, application/json;q=0.9");
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        String previousTrace = MDC.get("apiChatTraceId");
        MDC.put("apiChatTraceId", "trace-handler-test");

        try (LogCapture logs = LogCapture.start()) {
            ResponseEntity<ApiChatErrorResponse> response = handler.handle(
                    ApiChatException.invalid(
                            "Request contains an unsupported field.", "reasoning_effort"),
                    request,
                    servletResponse);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getHeaders().getContentType())
                    .isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(response.getBody().error().param()).isEqualTo("reasoning_effort");
            assertThat(logs.joined())
                    .contains("event=api_chat_error_handler_enter")
                    .contains("event=api_chat_error_handler_response")
                    .contains("diagnosticSchema=chat-diag-v1")
                    .contains("handler=API_CHAT_EXCEPTION")
                    .contains("apiErrorCode=invalid_request")
                    .contains("targetStatus=400")
                    .contains("requestAcceptClass=SSE_AND_JSON")
                    .contains("responseContentType=application/json")
                    .contains("committedBeforeMapping=false")
                    .doesNotContain("Request contains an unsupported field.");
        } finally {
            if (previousTrace == null) {
                MDC.remove("apiChatTraceId");
            } else {
                MDC.put("apiChatTraceId", previousTrace);
            }
        }
    }

    @Test
    void invalidRequestNegotiatesJsonWhenWorkerAcceptsSseAndJson() throws Exception {
        ApiChatCompletionService service = mock(ApiChatCompletionService.class);
        when(service.create(
                any(),
                any(com.fasterxml.jackson.databind.node.ObjectNode.class),
                org.mockito.ArgumentMatchers.nullable(String.class))).thenThrow(
                ApiChatException.invalid(
                        "Only text content parts are supported.",
                        "messages[1].content[0].type"));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ApiChatCompletionController(service))
                .setControllerAdvice(new ApiChatExceptionHandler())
                .setCustomArgumentResolvers(new ApiKeyPrincipalResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .build();

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                                 "stream":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.param")
                        .value("messages[1].content[0].type"));
    }

    @Test
    void oversizedToolDescriptionKeepsPreciseJsonParameterAndNoStoreHeaders()
            throws Exception {
        ApiChatCompletionService service = mock(ApiChatCompletionService.class);
        when(service.create(
                any(),
                any(com.fasterxml.jackson.databind.node.ObjectNode.class),
                org.mockito.ArgumentMatchers.nullable(String.class))).thenThrow(
                ApiChatException.invalid(
                        "Function tool description exceeds the allowed UTF-8 size.",
                        "tools[0].function.description"));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ApiChatCompletionController(service))
                .setControllerAdvice(new ApiChatExceptionHandler())
                .setCustomArgumentResolvers(new ApiKeyPrincipalResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .build();

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                                 "stream":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("CDN-Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.param")
                        .value("tools[0].function.description"));
    }

    @Test
    void clientControlledUnknownParameterStaysInResponseButIsRedactedFromLogs() {
        ApiChatExceptionHandler handler = new ApiChatExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/chat/completions");
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        String previousTrace = MDC.get("apiChatTraceId");
        MDC.put("apiChatTraceId", "trace-handler-redaction");

        try (LogCapture logs = LogCapture.start()) {
            ResponseEntity<ApiChatErrorResponse> response = handler.handle(
                    ApiChatException.invalid(
                            "Request contains an unsupported field.",
                            "client-secret-canary"),
                    request,
                    servletResponse);

            assertThat(response.getBody().error().param())
                    .isEqualTo("client-secret-canary");
            assertThat(logs.joined())
                    .contains("parameter=unsupported")
                    .doesNotContain("client-secret-canary");
        } finally {
            if (previousTrace == null) {
                MDC.remove("apiChatTraceId");
            } else {
                MDC.put("apiChatTraceId", previousTrace);
            }
        }
    }

    @Test
    void responsesUsesItsOwnSafeParameterAndValidationReason() {
        ApiChatExceptionHandler handler = new ApiChatExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/responses");
        request.addHeader(HttpHeaders.ACCEPT,
                "text/event-stream, application/json;q=0.9");
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        String previousTrace = MDC.get("apiChatTraceId");
        MDC.put("apiChatTraceId", "trace-responses-validation");

        try (LogCapture logs = LogCapture.start()) {
            ResponseEntity<ApiChatErrorResponse> response = handler.handle(
                    ApiChatException.invalid(
                            "max_output_tokens is below the supported minimum.",
                            "max_output_tokens",
                            ApiChatException.ValidationReason.BELOW_MINIMUM),
                    request,
                    servletResponse);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getHeaders().getContentType())
                    .isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(response.getBody().error().param())
                    .isEqualTo("max_output_tokens");
            assertThat(logs.joined())
                    .contains("event=api_responses_error_handler_enter")
                    .contains("event=api_responses_error_handler_response")
                    .contains("parameter=max_output_tokens")
                    .contains("validationReason=BELOW_MINIMUM")
                    .doesNotContain("parameter=unsupported")
                    .doesNotContain("max_output_tokens is below the supported minimum.");
        } finally {
            if (previousTrace == null) {
                MDC.remove("apiChatTraceId");
            } else {
                MDC.put("apiChatTraceId", previousTrace);
            }
        }
    }

    /**
     * 该解析器是来为独立 MockMvc 固定注入已认证 API Key 主体，使测试只覆盖 Chat 错误响应协商而不重复安全链测试。
     */
    private static final class ApiKeyPrincipalResolver implements HandlerMethodArgumentResolver {

        private static final ApiKeyPrincipal PRINCIPAL = new ApiKeyPrincipal(
                new byte[16], 2L, new byte[32], "A".repeat(43), Set.of(7L));

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == ApiKeyPrincipal.class;
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

    private record LogCapture(
            Logger logger,
            ListAppender<ILoggingEvent> appender,
            Level previousLevel) implements AutoCloseable {

        private static LogCapture start() {
            Logger logger = (Logger) LoggerFactory.getLogger(ApiChatExceptionHandler.class);
            Level previousLevel = logger.getLevel();
            logger.setLevel(Level.DEBUG);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            return new LogCapture(logger, appender, previousLevel);
        }

        private String joined() {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
        }

        private boolean hasLevel(Level level) {
            return appender.list.stream().anyMatch(event -> event.getLevel() == level);
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }
}
