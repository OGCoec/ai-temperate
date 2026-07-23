package com.example.temperate.web.auth.config.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 验证认证密钥、Cookie、CORS 与 TTL 启动期安全配置约束。
 */
class AuthSecurityPropertiesTest {

    private static final String VALID_JWT_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String VALID_HMAC_SECRET =
            "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=";

    private ApplicationContextRunner contextRunner(
            String jwtSecret,
            String hmacSecret,
            String allowedHost,
            String allowedOrigin,
            boolean cookieSecure,
            String emptyCacheTtl) {
        return contextRunner(
                jwtSecret,
                hmacSecret,
                allowedHost,
                allowedOrigin,
                cookieSecure,
                "",
                emptyCacheTtl);
    }

    private ApplicationContextRunner contextRunner(
            String jwtSecret,
            String hmacSecret,
            String allowedHost,
            String allowedOrigin,
            boolean cookieSecure,
            String cookieDomain,
            String emptyCacheTtl) {
        return new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(
                    "app.security.env=test",
                    "app.security.jwt.secret-base64=" + jwtSecret,
                    "app.security.hmac.secret-base64=" + hmacSecret,
                    "app.security.turnstile.site-key=1x00000000000000000000AA",
                    "app.security.turnstile.secret-key=1x0000000000000000000000000000000AA",
                    "app.security.turnstile.allowed-hosts[0]=" + allowedHost,
                    "app.security.cookies.domain=" + cookieDomain,
                    "app.security.cookies.access.secure=" + cookieSecure,
                    "app.security.cookies.access.http-only=true",
                    "app.security.cookies.access.same-site=strict",
                    "app.security.cookies.access.path=/api",
                    "app.security.cookies.refresh.secure=true",
                    "app.security.cookies.refresh.http-only=true",
                    "app.security.cookies.refresh.same-site=strict",
                    "app.security.cookies.refresh.path=/api/auth/session",
                    "app.security.cookies.csrf.secure=true",
                    "app.security.cookies.csrf.http-only=false",
                    "app.security.cookies.csrf.same-site=strict",
                    "app.security.cookies.csrf.path=/",
                    "app.security.cookies.register-flow.secure=true",
                    "app.security.cookies.register-flow.http-only=true",
                    "app.security.cookies.register-flow.same-site=strict",
                    "app.security.cookies.register-flow.path=/api/auth/register",
                    "app.security.cookies.register-challenge.secure=true",
                    "app.security.cookies.register-challenge.http-only=true",
                    "app.security.cookies.register-challenge.same-site=strict",
                    "app.security.cookies.register-challenge.path=/api/auth/register",
                    "app.security.cookies.password-reset-flow.secure=true",
                    "app.security.cookies.password-reset-flow.http-only=true",
                    "app.security.cookies.password-reset-flow.same-site=strict",
                    "app.security.cookies.password-reset-flow.path=/api/auth/password-reset",
                    "app.security.cookies.password-reset-forget.secure=true",
                    "app.security.cookies.password-reset-forget.http-only=true",
                    "app.security.cookies.password-reset-forget.same-site=strict",
                    "app.security.cookies.password-reset-forget.path=/api/auth/password-reset/complete",
                    "app.security.cors.allowed-origins[0]=" + allowedOrigin,
                    "app.security.ttl.access-token=15m",
                    "app.security.ttl.verification-code=5m",
                    "app.security.ttl.positive-cache=10m",
                    "app.security.ttl.empty-cache=" + emptyCacheTtl);
    }

    @Test
    void bindsValidatedImmutableSecurityConfiguration() {
        contextRunner(
                        VALID_JWT_SECRET,
                        VALID_HMAC_SECRET,
                        "localhost",
                        "http://localhost:5173",
                        true,
                        "45s")
                .run(context -> {
            assertThat(context).hasNotFailed();
            AuthSecurityProperties properties = context.getBean(AuthSecurityProperties.class);

            assertThat(properties.env()).isEqualTo(AuthSecurityProperties.Env.TEST);
            assertThat(properties.turnstile().allowedHosts()).containsExactly("localhost");
            assertThat(properties.cors().allowedOrigins())
                    .containsExactly("http://localhost:5173");
            assertThat(properties.cookies().domain()).isEmpty();
            assertThat(properties.cookies().access().path()).isEqualTo("/api");
            assertThat(properties.cookies().refresh().path())
                    .isEqualTo("/api/auth/session");
            assertThat(properties.cookies().csrf().httpOnly()).isFalse();
            assertThat(properties.cookies().registerFlow().path())
                    .isEqualTo("/api/auth/register");
            assertThat(properties.cookies().passwordResetForget().path())
                    .isEqualTo("/api/auth/password-reset/complete");
            assertThat(properties.ttl().positiveCache()).isEqualTo(Duration.ofMinutes(10));
            assertThat(properties.ttl().emptyCache()).isEqualTo(Duration.ofSeconds(45));
        });
    }

    @Test
    void bindsPublicCookieDomainForSubdomainTraffic() {
        contextRunner(
                        VALID_JWT_SECRET,
                        VALID_HMAC_SECRET,
                        "localhost",
                        "https://niko000o.site",
                        true,
                        "niko000o.site",
                        "45s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AuthSecurityProperties properties =
                            context.getBean(AuthSecurityProperties.class);

                    assertThat(properties.cookies().domain()).isEqualTo("niko000o.site");
                });
    }

    @Test
    void rejectsCookieDomainWithSchemeOrPort() {
        contextRunner(
                        VALID_JWT_SECRET,
                        VALID_HMAC_SECRET,
                        "localhost",
                        "https://niko000o.site",
                        true,
                        "https://niko000o.site:6655",
                        "45s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "Cookie domain must be empty or a valid hostname");
                });
    }

    @Test
    void rejectsMalformedOrWeakBase64Secrets() {
        contextRunner(
                        "not-base64",
                        VALID_HMAC_SECRET,
                        "localhost",
                        "http://localhost:5173",
                        true,
                        "45s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("JWT secret must be canonical Base64");
                });
    }

    @Test
    void rejectsMalformedHmacSecrets() {
        contextRunner(
                        VALID_JWT_SECRET,
                        "not-base64",
                        "localhost",
                        "http://localhost:5173",
                        true,
                        "45s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("HMAC secret must be canonical Base64");
                });
    }

    @Test
    void rejectsWildcardOriginsAndNonHostTurnstileEntries() {
        contextRunner(
                        VALID_JWT_SECRET,
                        VALID_HMAC_SECRET,
                        "https://localhost",
                        "*",
                        true,
                        "45s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("CORS origins must be explicit HTTP(S) origins")
                            .hasStackTraceContaining("Turnstile allowed hosts must be valid hostnames");
                });
    }

    @Test
    void rejectsUnsafeCookieFlagsAndOutOfRangeTtl() {
        contextRunner(
                        VALID_JWT_SECRET,
                        VALID_HMAC_SECRET,
                        "localhost",
                        "http://localhost:5173",
                        false,
                        "5m")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "Authentication cookie policies do not match the required transport contract")
                            .hasStackTraceContaining("Security TTL values are outside their bounded ranges");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthSecurityProperties.class)
    static class PropertiesConfiguration {
    }
}
