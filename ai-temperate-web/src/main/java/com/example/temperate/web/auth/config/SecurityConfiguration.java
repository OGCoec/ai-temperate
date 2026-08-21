package com.example.temperate.web.auth.config;

import com.example.temperate.service.user.voice.config.VoiceProperties;
import com.example.temperate.service.risk.ipintel.service.IpIntelligenceService;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.user.apikey.authentication.ApiKeyAuthenticationService;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.service.user.membership.MembershipExpirationService;
import com.example.temperate.web.apikey.ApiKeyAuthenticationFilter;
import com.example.temperate.web.apikey.ApiInferenceBodyLimitFilter;
import com.example.temperate.web.apikey.ApiKeyIpRiskFilter;
import com.example.temperate.web.apikey.ApiKeyV1Paths;
import com.example.temperate.web.apikey.OpenAiErrorResponseWriter;
import com.example.temperate.web.apichat.diagnostic.ApiChatStreamDiagnosticFilter;
import com.example.temperate.web.apiresponse.ApiResponsesTraceFilter;
import com.example.temperate.web.apiresponse.diagnostic.ApiResponsesStreamDiagnosticFilter;
import com.example.temperate.web.auth.config.properties.AuthSecurityProperties;
import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTraceFilter;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.edgeproxy.EdgeProxySignatureFilter;
import com.example.temperate.web.edgeproxy.TrustedEdgeNetworkContextResolver;
import com.example.temperate.web.risk.PreAuthTransport;
import com.example.temperate.web.risk.webrtc.WebRtcVerificationTransport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.DispatcherType;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import com.example.temperate.web.user.membership.payment.loadtest.MembershipPaymentLoadtestRequestPolicy;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 装配认证 Web 安全组件以及 H5、Android、语音和公开 API Key 的隔离过滤链。
 *
 * <p>该配置类负责提供签名密钥、密码编码器、Cookie CSRF 仓库、CORS 规则，以及按客户端传输协议拆分的
 * Spring Security 过滤链。平台头只用于选择 H5 Cookie 或 Android Header/请求体协议，不能作为认证凭据；
 * `/v1` 只接受 Worker 签名和 Bearer API Key。</p>
 */
@Configuration
@EnableConfigurationProperties(AuthSecurityProperties.class)
public class SecurityConfiguration {

    private static final String PLATFORM_HEADER = "X-Client-Platform";
    private static final String ANDROID = "ANDROID";
    private static final String CSRF_HEADER = "X-CSRF-Token";
    private static final String BOOTSTRAP_PATH = "/api/auth/session/bootstrap";
    private static final String WEBRTC_REPORT_PATH = "/api/_edge/webrtc/report";
    private static final String SIMULATED_PAYMENT_CALLBACK_PATH =
            "/internal/test/membership-payments/liuhao/notify";
    private static final String MEMBERSHIP_LOADTEST_CONTROL_ROOT =
            "/internal/test/membership-payments/loadtest-control";

    @Bean
    SecretKey jwtSigningKey(AuthSecurityProperties properties) {
        byte[] secret = Base64.getDecoder().decode(properties.jwt().secretBase64());
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager() {
        return authentication -> {
            throw new BadCredentialsException("Authentication is not configured");
        };
    }

    @Bean
    ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(
            ApiKeyAuthenticationService authenticationService,
            MembershipExpirationService membershipExpirationService,
            ApiKeyProperties properties,
            OpenAiErrorResponseWriter errorWriter) {
        return new ApiKeyAuthenticationFilter(
                authenticationService,
                membershipExpirationService,
                properties,
                errorWriter);
    }

    @Bean
    ApiKeyIpRiskFilter apiKeyIpRiskFilter(
            TrustedEdgeNetworkContextResolver edgeContextResolver,
            IpIntelligenceService ipIntelligenceService,
            ApiKeyProperties properties,
            NetworkRiskProperties networkRiskProperties,
            OpenAiErrorResponseWriter errorWriter,
            MeterRegistry meterRegistry) {
        return new ApiKeyIpRiskFilter(
                edgeContextResolver,
                ipIntelligenceService,
                properties,
                networkRiskProperties,
                errorWriter,
                meterRegistry);
    }

    @Bean
    ApiInferenceBodyLimitFilter apiInferenceBodyLimitFilter(
            ApiKeyProperties properties,
            OpenAiErrorResponseWriter errorWriter) {
        return new ApiInferenceBodyLimitFilter(properties, errorWriter);
    }

    /**
     * 公开 Chat 流诊断作为 Servlet 外层观察器覆盖 REQUEST、ASYNC 与 ERROR 分派，
     * 只记录生命周期元数据，不参与认证、授权或响应体转换。
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "app.api-key.stream-diagnostics",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    ApiChatStreamDiagnosticFilter apiChatStreamDiagnosticFilter(
            ApiKeyProperties properties) {
        return new ApiChatStreamDiagnosticFilter(properties, System::nanoTime);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "app.api-key.stream-diagnostics",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    FilterRegistrationBean<ApiChatStreamDiagnosticFilter>
            apiChatStreamDiagnosticFilterRegistration(
                    ApiChatStreamDiagnosticFilter filter) {
        FilterRegistrationBean<ApiChatStreamDiagnosticFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setName("apiChatStreamDiagnosticFilter");
        registration.setUrlPatterns(List.of("/v1/chat/completions"));
        registration.setDispatcherTypes(
                DispatcherType.REQUEST,
                DispatcherType.ASYNC,
                DispatcherType.ERROR);
        registration.setAsyncSupported(true);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /** Responses 使用独立轻量 Trace 过滤器，不复用或改变现有 chat-diag-v1 诊断契约。 */
    @Bean
    ApiResponsesTraceFilter apiResponsesTraceFilter() {
        return new ApiResponsesTraceFilter();
    }

    @Bean
    FilterRegistrationBean<ApiResponsesTraceFilter>
            apiResponsesTraceFilterRegistration(ApiResponsesTraceFilter filter) {
        FilterRegistrationBean<ApiResponsesTraceFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setName("apiResponsesTraceFilter");
        registration.setUrlPatterns(List.of("/v1/responses"));
        registration.setDispatcherTypes(
                DispatcherType.REQUEST,
                DispatcherType.ASYNC,
                DispatcherType.ERROR);
        registration.setAsyncSupported(true);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 11);
        return registration;
    }

    /**
     * Responses 流诊断复用 Trace 过滤器已经建立的请求标识，只观察 Servlet 异步生命周期，
     * 不生成新 Trace、不读取请求体，也不参与 SSE 内容和背压控制。
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "app.api-key.stream-diagnostics",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    ApiResponsesStreamDiagnosticFilter apiResponsesStreamDiagnosticFilter(
            ApiKeyProperties properties) {
        return new ApiResponsesStreamDiagnosticFilter(properties, System::nanoTime);
    }

    /**
     * 诊断过滤器必须紧跟 Responses Trace 过滤器，以便 REQUEST、ASYNC 与 ERROR 分派共享同一个
     * X-Trace-Id；它只精确覆盖 `/v1/responses`，其他 `/v1/**` 路径不进入该诊断链路。
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "app.api-key.stream-diagnostics",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    FilterRegistrationBean<ApiResponsesStreamDiagnosticFilter>
            apiResponsesStreamDiagnosticFilterRegistration(
                    ApiResponsesStreamDiagnosticFilter filter) {
        FilterRegistrationBean<ApiResponsesStreamDiagnosticFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setName("apiResponsesStreamDiagnosticFilter");
        registration.setUrlPatterns(List.of("/v1/responses"));
        registration.setDispatcherTypes(
                DispatcherType.REQUEST,
                DispatcherType.ASYNC,
                DispatcherType.ERROR);
        registration.setAsyncSupported(true);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 12);
        return registration;
    }

    /**
     * 三个 `/v1` 过滤器只允许由 Spring Security 按既定顺序执行，禁止 Servlet 容器再次自动注册造成双重认证、风控或请求体读取。
     */
    @Bean
    FilterRegistrationBean<ApiKeyAuthenticationFilter>
            apiKeyAuthenticationFilterRegistration(ApiKeyAuthenticationFilter filter) {
        return securityOnlyFilter(filter);
    }

    @Bean
    FilterRegistrationBean<ApiKeyIpRiskFilter>
            apiKeyIpRiskFilterRegistration(ApiKeyIpRiskFilter filter) {
        return securityOnlyFilter(filter);
    }

    @Bean
    FilterRegistrationBean<ApiInferenceBodyLimitFilter>
            apiInferenceBodyLimitFilterRegistration(ApiInferenceBodyLimitFilter filter) {
        return securityOnlyFilter(filter);
    }

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository(AuthSecurityProperties properties) {
        AuthSecurityProperties.Cookies cookies = properties.cookies();
        AuthSecurityProperties.CookieSettings settings = cookies.csrf();
        String cookieDomain = cookies.domain();
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        repository.setCookieName(AuthCookieWriter.CSRF_COOKIE);
        repository.setHeaderName(CSRF_HEADER);
        repository.setCookieCustomizer(builder -> {
            builder.secure(settings.secure())
                    .httpOnly(settings.httpOnly())
                    .sameSite(sameSite(settings.sameSite()))
                    .path(settings.path());
            // CSRF Cookie 必须与认证 Cookie 使用同一个 Domain，否则跨子域 H5 无法完成双提交校验。
            if (cookieDomain != null && !cookieDomain.isBlank()) {
                builder.domain(cookieDomain);
            }
        });
        return repository;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AuthSecurityProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.cors().allowedOrigins());
        configuration.setAllowCredentials(true);
        configuration.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization",
                "X-Refresh-Token",
                "Content-Type",
                "Idempotency-Key",
                "X-Device-Installation-Id",
                "X-AI-Client-Request-Id",
                PLATFORM_HEADER,
                "X-Register-Token",
                "X-Register-CSRF",
                "X-Login-Flow-Token",
                "X-OAuth-Flow-Token",
                "X-OAuth-Phone-Flow-Token",
                "X-TOTP-Flow-Token",
                "X-Reset-Flow-Token",
                "X-Forget-Token",
                "X-Turnstile-Challenge",
                AuthRequestTraceFilter.ATTEMPT_HEADER,
                PreAuthTransport.APP_HEADER,
                PreAuthTransport.RESET_HEADER,
                CSRF_HEADER));
        configuration.setExposedHeaders(List.of(
                HttpHeaders.ETAG,
                "Retry-After",
                AuthRequestTraceFilter.TRACE_HEADER,
                "X-AI-Generation-Id",
                "X-New-Access-Token",
                WebRtcVerificationTransport.STATE_HEADER,
                WebRtcVerificationTransport.GENERATION_HEADER,
                "X-Session-Renewed",
                "CF-Ray",
                "cf-mitigated"));
        configuration.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /**
     * 为公开 API Key v1 接口建立不含 Cookie、Session、CORS 和 CSRF 的精确无状态安全链。
     *
     * <p>该链依次完成 Edge HMAC、Bearer API Key 和权威 IP 风险；Chat Completions 与 Responses 再经过统一请求体大小门禁。
     * 普通 H5、Android 与语音请求仍进入各自既有安全链。</p>
     */
    @Bean
    @Order(0)
    SecurityFilterChain apiKeySecurityFilterChain(
            HttpSecurity http,
            EdgeProxySignatureFilter edgeProxySignatureFilter,
            ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
            ApiKeyIpRiskFilter apiKeyIpRiskFilter,
            ApiInferenceBodyLimitFilter apiInferenceBodyLimitFilter,
            OpenAiErrorResponseWriter errorWriter) throws Exception {
        // 公开 API 不创建 Session、不读取 Cookie、不启用 CORS，所有身份只来自当前 Bearer 和 Worker 签名。
        return http
                .securityMatcher(request ->
                        ApiKeyV1Paths.isApiKeyEndpoint(
                                request.getMethod(), request.getRequestURI()))
                .cors(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        SecurityConfiguration::configureApiChatAuthorization)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                errorWriter.write(
                                        response,
                                        HttpServletResponse.SC_UNAUTHORIZED,
                                        "Invalid API Key.",
                                        "authentication_error",
                                        "invalid_api_key")))
                .addFilterBefore(
                        edgeProxySignatureFilter,
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(
                        apiKeyAuthenticationFilter,
                        EdgeProxySignatureFilter.class)
                .addFilterAfter(
                        apiKeyIpRiskFilter,
                        ApiKeyAuthenticationFilter.class)
                .addFilterAfter(
                        apiInferenceBodyLimitFilter,
                        ApiKeyIpRiskFilter.class)
                .build();
    }

    /**
     * 初始 REQUEST 必须保留认证要求；ASYNC/ERROR 是 Servlet 容器对同一条已鉴权流的内部完成派发，
     * 客户端无法通过请求头伪造 DispatcherType，因此允许它们恢复响应不会扩大外部访问边界。
     */
    static void configureApiChatAuthorization(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                    authorize) {
        authorize
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                .permitAll()
                .anyRequest()
                .authenticated();
    }

    @Bean
    @Order(2)
    @ConditionalOnProperty(
            prefix = "app.voice",
            name = "enabled",
            havingValue = "true")
    SecurityFilterChain voiceWebSocketSecurityFilterChain(
            HttpSecurity http,
            @Qualifier("corsConfigurationSource")
                    CorsConfigurationSource corsConfigurationSource,
            EdgeProxySignatureFilter edgeProxySignatureFilter,
            VoiceProperties voiceProperties) throws Exception {
        configureCommon(http, corsConfigurationSource);
        return http
                .securityMatcher(request -> isVoiceWebSocketRequest(
                        request, voiceProperties.publicPath()))
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(edgeProxySignatureFilter, CorsFilter.class)
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain androidSecurityFilterChain(
            HttpSecurity http,
            @Qualifier("corsConfigurationSource")
                    CorsConfigurationSource corsConfigurationSource,
            EdgeProxySignatureFilter edgeProxySignatureFilter) throws Exception {
        // Android 不自动携带浏览器 Cookie，因此不适用 Spring 的双提交 Cookie CSRF 机制。
        configureCommon(http, corsConfigurationSource);
        return http
                .securityMatcher(SecurityConfiguration::isAndroidRequest)
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(edgeProxySignatureFilter, CorsFilter.class)
                .build();
    }

    @Bean
    @Order(4)
    SecurityFilterChain h5SecurityFilterChain(
            HttpSecurity http,
            @Qualifier("corsConfigurationSource")
                    CorsConfigurationSource corsConfigurationSource,
            @Qualifier("csrfTokenRepository")
                    CookieCsrfTokenRepository csrfTokenRepository,
            JsonCsrfAccessDeniedHandler csrfAccessDeniedHandler,
            EdgeProxySignatureFilter edgeProxySignatureFilter,
            MembershipPaymentLoadtestRequestPolicy loadtestRequestPolicy) throws Exception {
        // H5 由浏览器自动携带 Cookie，bootstrap 保留给后续 Origin、设备和 RT 组合校验。
        configureCommon(http, corsConfigurationSource);
        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers(
                                SecurityConfiguration::isCsrfExemptRequest,
                                loadtestRequestPolicy::matches,
                                loadtestRequestPolicy::matchesTokenMint))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(csrfAccessDeniedHandler))
                .addFilterBefore(edgeProxySignatureFilter, CorsFilter.class)
                .build();
    }

    static boolean isVoiceWebSocketRequest(
            HttpServletRequest request,
            String publicPath) {
        // Request URI 包含 Servlet Context Path；精确拼接可阻止尾斜杠、子路径和编码变体误入无 CSRF 的语音链。
        String contextPath = request.getContextPath();
        String expectedUri = (contextPath == null ? "" : contextPath) + publicPath;
        return expectedUri.equals(request.getRequestURI());
    }

    private static void configureCommon(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // 模拟支付回调只对 GET/POST 精确路径公开，业务认证由常量时间测试密钥校验完成。
                        .requestMatchers(
                                HttpMethod.GET,
                                SIMULATED_PAYMENT_CALLBACK_PATH)
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                SIMULATED_PAYMENT_CALLBACK_PATH)
                        .permitAll()
                        .requestMatchers(
                                "/api/health",
                                "/actuator/health/liveness",
                                "/actuator/health/readiness",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/doc.html",
                                "/webjars/**")
                        .permitAll()
                        .anyRequest()
                        .permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
    }

    private static boolean isCsrfExemptRequest(jakarta.servlet.http.HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod())
                && ((request.getContextPath() + BOOTSTRAP_PATH)
                                .equals(request.getRequestURI())
                        || (request.getContextPath() + "/api/_edge/pre-auth")
                                .equals(request.getRequestURI())
                        // WebRTC 报告发生在常规 CSRF 初始化之前，只豁免这一条精确 PreAuth 绑定路径。
                        || (request.getContextPath() + WEBRTC_REPORT_PATH)
                                .equals(request.getRequestURI())
                        // 模拟支付 POST 由独立测试密钥认证，只豁免这一条精确路径；GET 本身不受 CSRF 校验。
                        || (request.getContextPath() + SIMULATED_PAYMENT_CALLBACK_PATH)
                                .equals(request.getRequestURI())
                        // 控制入口只在 loadtest Profile 注册且由 Controller 校验回环地址；CSRF 仅豁免固定 POST 动作。
                        || (request.getContextPath()
                                        + MEMBERSHIP_LOADTEST_CONTROL_ROOT
                                        + "/recover-callback")
                                .equals(request.getRequestURI())
                        || (request.getContextPath()
                                        + MEMBERSHIP_LOADTEST_CONTROL_ROOT
                                        + "/recover-order")
                                .equals(request.getRequestURI())
                        || (request.getContextPath()
                                        + MEMBERSHIP_LOADTEST_CONTROL_ROOT
                                        + "/flush")
                                .equals(request.getRequestURI())
                        || (request.getContextPath()
                                        + MEMBERSHIP_LOADTEST_CONTROL_ROOT
                                        + "/state-batch")
                                .equals(request.getRequestURI())
                        || (request.getContextPath()
                                        + MEMBERSHIP_LOADTEST_CONTROL_ROOT
                                        + "/rabbit-retry")
                                .equals(request.getRequestURI())
                        || (request.getContextPath()
                                        + MEMBERSHIP_LOADTEST_CONTROL_ROOT
                                        + "/rabbit-poison")
                                .equals(request.getRequestURI()));
    }

    private static boolean isAndroidRequest(
            jakarta.servlet.http.HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        // 浏览器不能通过伪造 Android 平台头进入关闭 CSRF 的原生链；带 Origin 的请求始终归入 H5。
        return ANDROID.equalsIgnoreCase(request.getHeader(PLATFORM_HEADER))
                && (origin == null || origin.isBlank());
    }

    private static String sameSite(AuthSecurityProperties.SameSite sameSite) {
        String name = sameSite.name().toLowerCase();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static <T extends jakarta.servlet.Filter> FilterRegistrationBean<T>
            securityOnlyFilter(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
