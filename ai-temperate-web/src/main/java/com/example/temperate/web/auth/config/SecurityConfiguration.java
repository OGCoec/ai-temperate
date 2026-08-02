package com.example.temperate.web.auth.config;

import com.example.temperate.web.auth.config.properties.AuthSecurityProperties;
import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTraceFilter;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.edgeproxy.EdgeProxySignatureFilter;
import com.example.temperate.web.risk.PreAuthTransport;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 装配认证 Web 安全组件和 H5/Android 两套传输过滤链。
 *
 * <p>该配置类负责提供签名密钥、密码编码器、Cookie CSRF 仓库、CORS 规则，以及按客户端传输协议拆分的
 * Spring Security 过滤链。平台头只用于选择 H5 Cookie 或 Android Header/请求体协议，不能作为认证凭据。</p>
 */
@Configuration
@EnableConfigurationProperties(AuthSecurityProperties.class)
public class SecurityConfiguration {

    private static final String PLATFORM_HEADER = "X-Client-Platform";
    private static final String ANDROID = "ANDROID";
    private static final String CSRF_HEADER = "X-CSRF-Token";
    private static final String BOOTSTRAP_PATH = "/api/auth/session/bootstrap";
    private static final String WEBRTC_REPORT_PATH = "/api/_edge/webrtc/report";

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
                "Content-Type",
                "Idempotency-Key",
                "X-Device-Installation-Id",
                "X-AI-Client-Request-Id",
                PLATFORM_HEADER,
                "X-Register-Token",
                "X-Register-CSRF",
                "X-Login-Flow-Token",
                "X-Reset-Flow-Token",
                "X-Forget-Token",
                "X-Turnstile-Challenge",
                AuthRequestTraceFilter.ATTEMPT_HEADER,
                PreAuthTransport.APP_HEADER,
                PreAuthTransport.RESET_HEADER,
                CSRF_HEADER));
        configuration.setExposedHeaders(List.of(
                "Retry-After",
                AuthRequestTraceFilter.TRACE_HEADER,
                "X-AI-Generation-Id",
                "CF-Ray",
                "cf-mitigated"));
        configuration.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    @Order(2)
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
    @Order(3)
    SecurityFilterChain h5SecurityFilterChain(
            HttpSecurity http,
            @Qualifier("corsConfigurationSource")
                    CorsConfigurationSource corsConfigurationSource,
            @Qualifier("csrfTokenRepository")
                    CookieCsrfTokenRepository csrfTokenRepository,
            JsonCsrfAccessDeniedHandler csrfAccessDeniedHandler,
            EdgeProxySignatureFilter edgeProxySignatureFilter) throws Exception {
        // H5 由浏览器自动携带 Cookie，bootstrap 保留给后续 Origin、设备和 RT 组合校验。
        configureCommon(http, corsConfigurationSource);
        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers(SecurityConfiguration::isBootstrapRequest))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(csrfAccessDeniedHandler))
                .addFilterBefore(edgeProxySignatureFilter, CorsFilter.class)
                .build();
    }

    private static void configureCommon(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/health",
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

    private static boolean isBootstrapRequest(jakarta.servlet.http.HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod())
                && ((request.getContextPath() + BOOTSTRAP_PATH)
                                .equals(request.getRequestURI())
                        || (request.getContextPath() + "/api/_edge/pre-auth")
                                .equals(request.getRequestURI())
                        // WebRTC 报告发生在常规 CSRF 初始化之前，只豁免这一条精确 PreAuth 绑定路径。
                        || (request.getContextPath() + WEBRTC_REPORT_PATH)
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
}
