package com.example.temperate.web.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.ipintel.service.IpIntelligenceService;
import com.example.temperate.service.user.apikey.authentication.ApiKeyAuthenticationService;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.web.apikey.ApiChatBodyLimitFilter;
import com.example.temperate.web.apikey.ApiKeyAuthenticationFilter;
import com.example.temperate.web.apikey.ApiKeyIpRiskFilter;
import com.example.temperate.web.apikey.OpenAiErrorResponseWriter;
import com.example.temperate.web.edgeproxy.TrustedEdgeNetworkContextResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.DispatcherType;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.util.WebUtils;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * 该测试是来验证 API Chat 初始 REQUEST 仍需认证，而容器内部 ASYNC/ERROR 完成派发不会再次触发三道 API Key 门禁。
 */
final class ApiChatSecurityAsyncDispatchRegressionTest {

    @Test
    void authenticatedRequestCanFinishOnAsyncDispatchWhileInitialRequestStaysProtected()
            throws Exception {
        try (AnnotationConfigWebApplicationContext context = context()) {
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                    .apply(springSecurity())
                    .build();
            ProbeController controller = context.getBean(ProbeController.class);

            mockMvc.perform(post("/v1/chat/completions"))
                    .andExpect(status().isUnauthorized());

            MvcResult pending = mockMvc.perform(post("/v1/chat/completions")
                            .with(user("api-key-client")))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            controller.complete("stream-complete");
            pending.getAsyncResult(1_000L);

            mockMvc.perform(asyncDispatch(pending))
                    .andExpect(status().isOk())
                    .andExpect(content().string("stream-complete"));

            MvcResult errorDispatch = mockMvc.perform(post("/v1/chat/completions")
                            .with(request -> {
                                request.setDispatcherType(DispatcherType.ERROR);
                                return request;
                            }))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            controller.complete("error-dispatch-resumed");
            errorDispatch.getAsyncResult(1_000L);
            mockMvc.perform(asyncDispatch(errorDispatch))
                    .andExpect(status().isOk())
                    .andExpect(content().string("error-dispatch-resumed"));

            mockMvc.perform(get("/ordinary"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("ordinary"));
        }
    }

    @Test
    void apiKeyFiltersSkipContainerAsyncAndErrorDispatches() throws Exception {
        ApiKeyAuthenticationService authenticationService =
                mock(ApiKeyAuthenticationService.class);
        TrustedEdgeNetworkContextResolver edgeResolver =
                mock(TrustedEdgeNetworkContextResolver.class);
        IpIntelligenceService intelligenceService = mock(IpIntelligenceService.class);
        OpenAiErrorResponseWriter errorWriter = mock(OpenAiErrorResponseWriter.class);
        ApiKeyProperties apiKeyProperties = new ApiKeyProperties();
        NetworkRiskProperties riskProperties = mock(NetworkRiskProperties.class);
        ApiKeyAuthenticationFilter authenticationFilter = new ApiKeyAuthenticationFilter(
                authenticationService,
                apiKeyProperties,
                errorWriter);
        ApiKeyIpRiskFilter riskFilter = new ApiKeyIpRiskFilter(
                edgeResolver,
                intelligenceService,
                apiKeyProperties,
                riskProperties,
                errorWriter,
                new SimpleMeterRegistry());
        ApiChatBodyLimitFilter bodyFilter = new ApiChatBodyLimitFilter(
                apiKeyProperties,
                errorWriter);

        for (DispatcherType dispatcherType :
                new DispatcherType[] {DispatcherType.ASYNC, DispatcherType.ERROR}) {
            MockHttpServletRequest request = new MockHttpServletRequest(
                    "POST", "/v1/chat/completions");
            request.setDispatcherType(dispatcherType);
            if (dispatcherType == DispatcherType.ASYNC) {
                // OncePerRequestFilter 依据 Spring MVC 的并发结果标记识别 ASYNC，而不只读取 DispatcherType。
                WebAsyncManager asyncManager = mock(WebAsyncManager.class);
                when(asyncManager.hasConcurrentResult()).thenReturn(true);
                request.setAttribute(WebAsyncUtils.WEB_ASYNC_MANAGER_ATTRIBUTE, asyncManager);
            } else {
                // ERROR 重派发以容器写入的错误请求 URI 属性识别；Mock 请求需显式模拟该契约。
                request.setAttribute(WebUtils.ERROR_REQUEST_URI_ATTRIBUTE, request.getRequestURI());
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicInteger continuations = new AtomicInteger();

            authenticationFilter.doFilter(
                    request, response, (ignoredRequest, ignoredResponse) ->
                            continuations.incrementAndGet());
            riskFilter.doFilter(
                    request, response, (ignoredRequest, ignoredResponse) ->
                            continuations.incrementAndGet());
            bodyFilter.doFilter(
                    request, response, (ignoredRequest, ignoredResponse) ->
                            continuations.incrementAndGet());

            assertThat(continuations).hasValue(3);
        }

        verifyNoInteractions(authenticationService, edgeResolver, intelligenceService);
    }

    private static AnnotationConfigWebApplicationContext context() {
        AnnotationConfigWebApplicationContext context =
                new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestSecurityConfiguration.class, ProbeController.class);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    static class TestSecurityConfiguration {

        @Bean
        SecurityFilterChain testApiChatChain(HttpSecurity http) throws Exception {
            return http
                    .securityMatcher("/v1/chat/completions")
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(
                            SecurityConfiguration::configureApiChatAuthorization)
                    .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                            new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                    .build();
        }
    }

    /** 以可控 DeferredResult 复现 Spring MVC 在流完成后的 ASYNC 重派发。 */
    @RestController
    static final class ProbeController {
        private volatile DeferredResult<String> pending;

        @PostMapping("/v1/chat/completions")
        public DeferredResult<String> stream() {
            DeferredResult<String> result = new DeferredResult<>();
            pending = result;
            return result;
        }

        @GetMapping("/ordinary")
        public String ordinary() {
            return "ordinary";
        }

        void complete(String value) {
            DeferredResult<String> result = pending;
            if (result == null) {
                throw new IllegalStateException("No pending API Chat request");
            }
            result.setResult(value);
        }
    }
}
