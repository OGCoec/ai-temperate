package com.example.temperate.web.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.service.user.apikey.authentication.ApiKeyAuthenticationException;
import com.example.temperate.service.user.apikey.authentication.ApiKeyAuthenticationService;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 该测试是来保证 Models 与 Chat 共用 API Key 认证及统一错误边界，而 Chat 请求体限制不会错误读取无请求体的 Models GET。
 */
final class ApiKeyV1FiltersTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesModelDiscoveryWithTheSameBearerFilterAsChatCompletions() throws Exception {
        ApiKeyAuthenticationService authenticationService = mock(ApiKeyAuthenticationService.class);
        ApiKeyPrincipal principal = new ApiKeyPrincipal(
                1L, 2L, new byte[32], "A".repeat(43), Set.of(7L));
        when(authenticationService.authenticate("sk-test")).thenReturn(principal);
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setEnabled(true);
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(
                authenticationService,
                properties,
                new OpenAiErrorResponseWriter(new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        request.addHeader("Authorization", "Bearer sk-test");
        request.addHeader("Sec-Fetch-Mode", "cors");
        request.addHeader("Sec-Fetch-Site", "none");
        request.addHeader("Sec-Fetch-Dest", "empty");
        request.addHeader("Sec-Fetch-User", "?1");
        AtomicReference<Object> authenticatedPrincipal = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) ->
                authenticatedPrincipal.set(SecurityContextHolder.getContext()
                        .getAuthentication().getPrincipal()));

        verify(authenticationService).authenticate("sk-test");
        assertThat(authenticatedPrincipal.get()).isSameAs(principal);
    }

    @Test
    void authenticatesChatCompletionsWhenNodeFetchAddsFetchMetadata() throws Exception {
        ApiKeyAuthenticationService authenticationService = mock(ApiKeyAuthenticationService.class);
        ApiKeyPrincipal principal = new ApiKeyPrincipal(
                1L, 2L, new byte[32], "A".repeat(43), Set.of(7L));
        when(authenticationService.authenticate("sk-test")).thenReturn(principal);
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setEnabled(true);
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(
                authenticationService,
                properties,
                new OpenAiErrorResponseWriter(new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        request.addHeader("Authorization", "Bearer sk-test");
        request.addHeader("Sec-Fetch-Mode", "cors");
        AtomicReference<Object> authenticatedPrincipal = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) ->
                authenticatedPrincipal.set(SecurityContextHolder.getContext()
                        .getAuthentication().getPrincipal()));

        verify(authenticationService).authenticate("sk-test");
        assertThat(authenticatedPrincipal.get()).isSameAs(principal);
    }

    @Test
    void rejectsMissingBearerForModelDiscoveryWithOpenAiUnauthorizedResponse() throws Exception {
        ApiKeyAuthenticationService authenticationService = mock(ApiKeyAuthenticationService.class);
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setEnabled(true);
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(
                authenticationService,
                properties,
                new OpenAiErrorResponseWriter(new ObjectMapper()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                new MockHttpServletRequest("GET", "/v1/models"),
                response,
                (ignoredRequest, ignoredResponse) -> {
                    throw new AssertionError("Models request without a Bearer Key must stop");
                });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("invalid_api_key");
        verifyNoInteractions(authenticationService);
    }

    @Test
    void rejectsMalformedBearerForModelDiscoveryWithOpenAiUnauthorizedResponse() throws Exception {
        ApiKeyAuthenticationService authenticationService = mock(ApiKeyAuthenticationService.class);
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setEnabled(true);
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(
                authenticationService,
                properties,
                new OpenAiErrorResponseWriter(new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        request.addHeader("Authorization", "Basic not-a-bearer-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("Models request with a malformed Bearer Key must stop");
        });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("invalid_api_key");
        verifyNoInteractions(authenticationService);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("apiKeyEndpoints")
    void rejectsInvalidApiKeyFromAuthenticationServiceForEveryPublicEndpoint(
            String method,
            String path) throws Exception {
        ApiKeyAuthenticationService authenticationService = mock(ApiKeyAuthenticationService.class);
        when(authenticationService.authenticate("sk-test"))
                .thenThrow(new ApiKeyAuthenticationException());
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setEnabled(true);
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(
                authenticationService,
                properties,
                new OpenAiErrorResponseWriter(new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", "Bearer sk-test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                chainCalled.set(true));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("authentication_error", "invalid_api_key")
                .doesNotContain("disabled", "deleted", "expired");
        assertThat(chainCalled.get()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(authenticationService).authenticate("sk-test");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Cookie", "Origin", "Referer"})
    void rejectsBrowserCredentialHeadersForModelDiscoveryBeforeKeyLookup(String forbiddenHeader)
            throws Exception {
        ApiKeyAuthenticationService authenticationService = mock(ApiKeyAuthenticationService.class);
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setEnabled(true);
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(
                authenticationService,
                properties,
                new OpenAiErrorResponseWriter(new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        request.addHeader("Authorization", "Bearer sk-test");
        request.addHeader(forbiddenHeader, browserCredentialValue(forbiddenHeader));
        request.addHeader("Sec-Fetch-Mode", "cors");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("Browser-shaped Models request must stop");
        });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("browser_request_not_allowed");
        verifyNoInteractions(authenticationService);
    }

    private static String browserCredentialValue(String headerName) {
        return switch (headerName) {
            case "Cookie" -> "session=forbidden";
            case "Origin" -> "https://niko000o.site";
            case "Referer" -> "https://niko000o.site/";
            default -> throw new IllegalArgumentException("Unsupported test header: " + headerName);
        };
    }

    private static Stream<Arguments> apiKeyEndpoints() {
        return Stream.of(
                Arguments.of("GET", "/v1/models"),
                Arguments.of("POST", "/v1/chat/completions"));
    }

    @Test
    void doesNotTreatTrailingSlashAsAnApiKeyModelsEndpoint() throws Exception {
        ApiKeyAuthenticationService authenticationService = mock(ApiKeyAuthenticationService.class);
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setEnabled(true);
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(
                authenticationService,
                properties,
                new OpenAiErrorResponseWriter(new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models/");
        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);

        filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) ->
                chainCalled.set(true));

        verifyNoInteractions(authenticationService);
        assertThat(chainCalled.get()).isTrue();
    }

    @Test
    void chatBodyLimitFilterLeavesModelsGetRequestUntouched() throws Exception {
        ApiKeyProperties properties = new ApiKeyProperties();
        ApiChatBodyLimitFilter filter = new ApiChatBodyLimitFilter(
                properties, new OpenAiErrorResponseWriter(new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        AtomicReference<Object> forwardedRequest = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (wrappedRequest, ignoredResponse) ->
                forwardedRequest.set(wrappedRequest));

        assertThat(forwardedRequest.get()).isSameAs(request);
    }
}
