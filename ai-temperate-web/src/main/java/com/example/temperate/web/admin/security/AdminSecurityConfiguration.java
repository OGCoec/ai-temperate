package com.example.temperate.web.admin.security;

import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.web.admin.transport.AdminCookieWriter;
import com.example.temperate.web.auth.config.SpaCsrfTokenRequestHandler;
import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTraceFilter;
import com.example.temperate.web.edgeproxy.EdgeProxySignatureFilter;
import com.example.temperate.web.risk.PreAuthTransport;
import com.example.temperate.web.risk.webrtc.WebRtcVerificationTransport;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 为 {@code /api/admin/**} 建立先于普通用户链的独立 Edge 签名、CORS 与 CSRF 安全边界。
 *
 * <p>管理员配置状态和会话认证属于 MVC 业务门，由 {@link AdminWebMvcConfiguration} 在网络风险与
 * WebRTC 之后注册；这里禁止再次把它们加入 Security Filter Chain。</p>
 */
@Configuration
public class AdminSecurityConfiguration {

    private static final String PLATFORM_HEADER = "X-Client-Platform";

    private final AdminClientPlatformResolver platformResolver;

    public AdminSecurityConfiguration(AdminClientPlatformResolver platformResolver) {
        this.platformResolver = platformResolver;
    }

    @Bean
    CookieCsrfTokenRepository adminCsrfTokenRepository(AdminProperties properties) {
        AdminProperties.Cookie settings = properties.cookies().csrf();
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        repository.setCookieName(AdminCookieWriter.CSRF_COOKIE);
        repository.setHeaderName("X-Admin-CSRF-Token");
        repository.setCookieCustomizer(builder -> {
            builder.secure(settings.secure())
                    .httpOnly(settings.httpOnly())
                    .sameSite(settings.sameSite())
                    .path(settings.path());
            // 生产环境删除 Domain 配置后由当前管理员 Host 保存 Cookie；该分支仅保留受控迁移和回滚能力。
            String domain = properties.cookies().csrfDomain();
            if (domain != null && !domain.isBlank()) {
                builder.domain(domain);
            }
        });
        return repository;
    }

    @Bean
    CorsConfigurationSource adminCorsConfigurationSource(AdminProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowCredentials(true);
        // Worker 转发会保留管理员前端 Origin；写操作必须在 CORS 边界允许后才会进入 CSRF 和会话校验。
        configuration.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "X-Device-Installation-Id",
                AuthRequestTraceFilter.CLIENT_REQUEST_HEADER,
                AuthRequestTraceFilter.PAGE_INSTANCE_HEADER,
                AuthRequestTraceFilter.CLIENT_QUEUE_HEADER,
                AuthRequestTraceFilter.WEBRTC_PROBE_RUN_HEADER,
                PLATFORM_HEADER,
                "X-Admin-Register-Token",
                "X-Admin-Login-Flow-Token",
                "X-Admin-CSRF-Token",
                "Idempotency-Key",
                "Last-Event-ID",
                "X-Trace-Id",
                "X-Admin-Challenge",
                PreAuthTransport.APP_HEADER,
                PreAuthTransport.RESET_HEADER,
                "X-Auth-Attempt-Id"));
        configuration.setExposedHeaders(List.of(
                "X-Trace-Id",
                "X-Accel-Buffering",
                WebRtcVerificationTransport.STATE_HEADER,
                WebRtcVerificationTransport.GENERATION_HEADER,
                AuthRequestTraceFilter.WEBRTC_PROBE_RUN_HEADER,
                "Idempotency-Replayed"));
        configuration.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/admin/**", configuration);
        return source;
    }

    @Bean
    @Order(1)
    SecurityFilterChain adminSecurityFilterChain(
            HttpSecurity http,
            @Qualifier("adminCorsConfigurationSource")
                    CorsConfigurationSource adminCorsConfigurationSource,
            @Qualifier("adminCsrfTokenRepository")
                    CookieCsrfTokenRepository adminCsrfTokenRepository,
            AdminCsrfAccessDeniedHandler csrfAccessDeniedHandler,
            EdgeProxySignatureFilter edgeProxySignatureFilter) throws Exception {
        // 管理员公开 GET 主动解析延迟 Token，使注册或登录页面刷新后能稳定恢复双提交 CSRF；
        // 管理员 Session Token 仍只能由登录成功或有效会话恢复流程签发。
        SpaCsrfTokenRequestHandler csrfTokenRequestHandler =
                new SpaCsrfTokenRequestHandler();
        return http
                .securityMatcher("/api/admin/**")
                .cors(cors -> cors.configurationSource(adminCorsConfigurationSource))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(adminCsrfTokenRepository)
                        .csrfTokenRequestHandler(csrfTokenRequestHandler)
                        .ignoringRequestMatchers(this::ignoreCsrf))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(csrfAccessDeniedHandler))
                .addFilterBefore(
                        edgeProxySignatureFilter,
                        CorsFilter.class)
                .build();
    }

    private boolean ignoreCsrf(jakarta.servlet.http.HttpServletRequest request) {
        if (platformResolver.isAndroid(request)) {
            return true;
        }
        String context = request.getContextPath();
        String uri = request.getRequestURI();
        String path = context == null || context.isEmpty()
                ? uri
                : uri.substring(context.length());
        // 注册和登录前置 Flow 使用各自 Cookie/Header 双提交；会话 bootstrap 与 WebRTC Report 尚未拥有管理员 XSRF Cookie。
        return path.startsWith("/api/admin/auth/register/")
                || path.startsWith("/api/admin/auth/login/")
                || path.equals("/api/admin/_edge/pre-auth")
                || (HttpMethod.POST.matches(request.getMethod())
                        && path.equals("/api/admin/_edge/webrtc/report"))
                || path.equals("/api/admin/auth/session/bootstrap");
    }
}
