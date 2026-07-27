package com.example.temperate.service.admin.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定管理员配置文件、短期流程、会话、Cookie、CORS 和 hCaptcha 的安全参数。
 *
 * <p>Secret 必须由环境变量提供；该对象只负责配置边界，不承载请求级状态。</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.admin")
public record AdminProperties(
        @NotNull Path configPath,
        @NotBlank String sessionHmacSecretBase64,
        @NotNull Duration sessionTtl,
        @Min(1) @Max(10) int maxSessions,
        @NotNull Duration registrationFlowTtl,
        @NotNull Duration loginFlowTtl,
        @NotEmpty List<String> allowedOrigins,
        @Valid @NotNull Hcaptcha hcaptcha,
        @Valid @NotNull Cookies cookies) {

    public AdminProperties {
        requirePositive(sessionTtl, "sessionTtl");
        requirePositive(registrationFlowTtl, "registrationFlowTtl");
        requirePositive(loginFlowTtl, "loginFlowTtl");
    }

    @Override
    public String toString() {
        return "AdminProperties[redacted]";
    }

    /**
     * 为不启动 Spring 上下文的文件存储单元测试提供完整且无生产 Secret 的配置。
     */
    public static AdminProperties testDefaults(Path configPath) {
        return new AdminProperties(
                configPath,
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                Duration.ofHours(6),
                10,
                Duration.ofMinutes(10),
                Duration.ofMinutes(10),
                List.of("https://admin.example.test"),
                new Hcaptcha(
                        "test-site-key",
                        "test-secret-key",
                        List.of("admin.example.test"),
                        Duration.ofSeconds(8),
                        Duration.ofSeconds(8),
                        Duration.ofMinutes(2)),
                new Cookies(
                        "",
                        "",
                        new Cookie(true, true, "STRICT", "/api/admin"),
                        new Cookie(true, false, "STRICT", "/"),
                        new Cookie(true, true, "STRICT", "/api/admin/auth/register"),
                        new Cookie(true, false, "STRICT", "/"),
                        new Cookie(true, true, "STRICT", "/api/admin/auth/login"),
                        new Cookie(true, false, "STRICT", "/")));
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /**
     * hCaptcha Siteverify 所需的公开键、Secret、域名绑定和网络时间边界。
     */
    public record Hcaptcha(
            @NotBlank String siteKey,
            @NotBlank String secretKey,
            @NotEmpty List<String> allowedHosts,
            @NotNull Duration connectTimeout,
            @NotNull Duration responseTimeout,
            @NotNull Duration maxChallengeAge) {

        public Hcaptcha {
            requirePositive(connectTimeout, "hcaptcha.connectTimeout");
            requirePositive(responseTimeout, "hcaptcha.responseTimeout");
            requirePositive(maxChallengeAge, "hcaptcha.maxChallengeAge");
        }

        @Override
        public String toString() {
            return "AdminProperties.Hcaptcha[redacted]";
        }
    }

    /**
     * 管理员 H5 敏感 Cookie、可读 CSRF Cookie 的独立 Domain 和分用途安全属性。
     *
     * <p>正式同源 Worker 架构下两类 Domain 均保持为空，由浏览器在管理员前端 Host 创建 Host-only
     * Cookie；非空父域只为迁移或回滚窗口保留，不能作为正式配置。</p>
     */
    public record Cookies(
            @Size(max = 253) String domain,
            @Size(max = 253) String csrfDomain,
            @Valid @NotNull Cookie session,
            @Valid @NotNull Cookie csrf,
            @Valid @NotNull Cookie registerFlow,
            @Valid @NotNull Cookie registerCsrf,
            @Valid @NotNull Cookie loginFlow,
            @Valid @NotNull Cookie loginCsrf) {

        public Cookies {
            domain = normalizeCookieDomain(domain);
            csrfDomain = normalizeCookieDomain(csrfDomain);
        }

        @AssertTrue(
                message =
                        "Administrator cookie domains must be empty or valid hostnames")
        public boolean isDomainsValid() {
            return isValidOptionalCookieDomain(domain)
                    && isValidOptionalCookieDomain(csrfDomain);
        }
    }

    /**
     * 单个管理员 Cookie 的 Secure、HttpOnly、SameSite 和 Path 约束。
     */
    public record Cookie(
            boolean secure,
            boolean httpOnly,
            @NotBlank String sameSite,
            @NotBlank String path) {
    }

    private static String normalizeCookieDomain(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isValidOptionalCookieDomain(String value) {
        return value.isEmpty()
                || (value.contains(".")
                && !value.equals("localhost")
                && !value.endsWith(".localhost")
                && value.matches(
                        "^(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
                                + "(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$"));
    }
}
