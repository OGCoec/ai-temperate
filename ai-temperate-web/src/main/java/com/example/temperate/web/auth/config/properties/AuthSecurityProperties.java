package com.example.temperate.web.auth.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定并校验认证安全配置，集中约束 JWT/HMAC 密钥、Turnstile、Cookie、CORS 和 TTL 的启动边界。
 *
 * <p>这个类型只负责把外部配置收敛成强类型安全契约，不负责执行业务认证流程。启动期拒绝弱密钥、宽松
 * Cookie 策略、非法 CORS Origin 和非法共享域名，可以避免应用带着不安全配置进入运行态。</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record AuthSecurityProperties(
        @NotNull Env env,
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Hmac hmac,
        @Valid @NotNull Turnstile turnstile,
        @Valid @NotNull Cookies cookies,
        @Valid @NotNull Cors cors,
        @Valid @NotNull Ttl ttl) {

    public enum Env {
        LOCAL,
        TEST,
        PROD
    }

    public enum SameSite {
        STRICT,
        LAX,
        NONE
    }

    public record Jwt(@NotBlank String secretBase64) {

        @AssertTrue(message = "JWT secret must be canonical Base64 containing at least 32 bytes")
        public boolean isSecretValid() {
            return isCanonicalBase64Secret(secretBase64);
        }
    }

    public record Hmac(@NotBlank String secretBase64) {

        @AssertTrue(message = "HMAC secret must be canonical Base64 containing at least 32 bytes")
        public boolean isSecretValid() {
            return isCanonicalBase64Secret(secretBase64);
        }
    }

    public record Turnstile(
            @NotBlank @Size(min = 20, max = 200)
                    @Pattern(regexp = "^[A-Za-z0-9_-]+$") String siteKey,
            @NotBlank @Size(min = 20, max = 200)
                    @Pattern(regexp = "^[A-Za-z0-9_-]+$") String secretKey,
            @NotEmpty List<@NotBlank String> allowedHosts) {

        @AssertTrue(message = "Turnstile allowed hosts must be valid hostnames")
        public boolean isAllowedHostsValid() {
            return allowedHosts != null
                    && !allowedHosts.isEmpty()
                    && allowedHosts.stream().distinct().count() == allowedHosts.size()
                    && allowedHosts.stream().allMatch(AuthSecurityProperties::isValidHostname);
        }
    }

    public record Cookies(
            @Valid @NotNull CookieSettings access,
            @Valid @NotNull CookieSettings refresh,
            @Valid @NotNull CookieSettings csrf,
            @Valid @NotNull CookieSettings registerFlow,
            @Valid @NotNull CookieSettings registerChallenge,
            @Valid @NotNull CookieSettings passwordResetFlow,
            @Valid @NotNull CookieSettings passwordResetForget,
            @Valid @NotNull CookieSettings totpLoginFlow,
            @Size(max = 253) String domain) {

        public Cookies {
            domain = normalizeCookieDomain(domain);
        }

        @AssertTrue(message = "Cookie domain must be empty or a valid hostname")
        public boolean isDomainValid() {
            return domain.isEmpty() || isValidCookieDomain(domain);
        }

        @AssertTrue(
                message =
                        "Authentication cookie policies do not match the required transport contract")
        public boolean isTransportContractValid() {
            return isExactCookiePolicy(access, true, "/api")
                    && isExactCookiePolicy(refresh, true, "/api")
                    && isExactCookiePolicy(csrf, false, "/")
                    && isExactCookiePolicy(registerFlow, true, "/api/auth/register")
                    && isExactCookiePolicy(registerChallenge, true, "/api/auth/register")
                    && isExactCookiePolicy(passwordResetFlow, true,
                            "/api/auth/password-reset")
                    && isExactCookiePolicy(passwordResetForget, true,
                            "/api/auth/password-reset/complete")
                    && isExactCookiePolicy(totpLoginFlow, true,
                            "/api/auth/login/totp");
        }
    }

    public record CookieSettings(
            @NotNull Boolean secure,
            @NotNull Boolean httpOnly,
            @NotNull SameSite sameSite,
            @NotBlank @Pattern(regexp = "^/[^\\s]*$") String path) {

        @AssertTrue(message = "SameSite=None cookies must be Secure")
        public boolean isSameSitePolicyValid() {
            return sameSite != SameSite.NONE || Boolean.TRUE.equals(secure);
        }
    }

    public record Cors(@NotEmpty List<@NotBlank String> allowedOrigins) {

        @AssertTrue(message = "CORS origins must be explicit HTTP(S) origins")
        public boolean isAllowedOriginsValid() {
            return allowedOrigins != null
                    && !allowedOrigins.isEmpty()
                    && allowedOrigins.stream().distinct().count() == allowedOrigins.size()
                    && allowedOrigins.stream().allMatch(AuthSecurityProperties::isValidOrigin);
        }
    }

    public record Ttl(
            @NotNull Duration accessToken,
            @NotNull Duration verificationCode,
            @NotNull Duration positiveCache,
            @NotNull Duration emptyCache) {

        @AssertTrue(message = "Security TTL values are outside their bounded ranges")
        public boolean isRangesValid() {
            return isBetween(accessToken, Duration.ofMinutes(1), Duration.ofHours(24))
                    && isBetween(verificationCode, Duration.ofSeconds(30), Duration.ofMinutes(15))
                    && isBetween(positiveCache, Duration.ofMinutes(5), Duration.ofMinutes(15))
                    && isBetween(emptyCache, Duration.ofSeconds(30), Duration.ofSeconds(60));
        }
    }

    @AssertTrue(
            message =
                    "Production security configuration requires HTTPS origins and non-local Turnstile hosts")
    public boolean isProductionPolicyValid() {
        if (env != Env.PROD || cors == null || turnstile == null) {
            return true;
        }
        return cors.allowedOrigins() != null
                && cors.allowedOrigins().stream().allMatch(origin -> origin.startsWith("https://"))
                && turnstile.allowedHosts() != null
                && turnstile.allowedHosts().stream()
                        .noneMatch(host -> host.equals("localhost") || host.endsWith(".localhost"));
    }

    private static boolean isCanonicalBase64Secret(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return decoded.length >= 32 && Base64.getEncoder().encodeToString(decoded).equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String normalizeCookieDomain(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isValidCookieDomain(String value) {
        return isValidHostname(value)
                && value.contains(".")
                && !value.equals("localhost")
                && !value.endsWith(".localhost");
    }

    private static boolean isValidHostname(String value) {
        if (value == null || !value.equals(value.toLowerCase(Locale.ROOT))) {
            return false;
        }
        return value.matches(
                "^(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$");
    }

    private static boolean isValidOrigin(String value) {
        if (value == null || value.contains("*")) {
            return false;
        }
        try {
            URI origin = URI.create(value);
            String scheme = origin.getScheme();
            return ("http".equals(scheme) || "https".equals(scheme))
                    && origin.getHost() != null
                    && origin.getRawUserInfo() == null
                    && origin.getRawQuery() == null
                    && origin.getRawFragment() == null
                    && (origin.getRawPath() == null || origin.getRawPath().isEmpty());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isExactCookiePolicy(
            CookieSettings settings, boolean httpOnly, String path) {
        // 认证与流程 Cookie 的 Path、Secure、HttpOnly 与 SameSite 是固定安全边界，启动期必须整体校验。
        return settings != null
                && Boolean.TRUE.equals(settings.secure())
                && Boolean.valueOf(httpOnly).equals(settings.httpOnly())
                && settings.sameSite() == SameSite.STRICT
                && path.equals(settings.path());
    }

    private static boolean isBetween(Duration value, Duration minimum, Duration maximum) {
        return value != null && value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    }
}
