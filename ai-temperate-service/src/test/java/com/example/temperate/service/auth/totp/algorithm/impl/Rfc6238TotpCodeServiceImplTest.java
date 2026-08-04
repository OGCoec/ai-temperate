package com.example.temperate.service.auth.totp.algorithm.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.auth.totp.config.TotpProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * 验证 TOTP 算法、密钥长度和二维码配置 URI 的固定安全契约。
 */
class Rfc6238TotpCodeServiceImplTest {

    private final Rfc6238TotpCodeServiceImpl service =
            new Rfc6238TotpCodeServiceImpl(properties());

    @Test
    void generatesThirtyTwoRandomBytesAndFiftyTwoBase32Characters() {
        byte[] secret = service.newSecret();

        assertThat(secret).hasSize(32);
        assertThat(service.encodeBase32(secret))
                .hasSize(52)
                .matches("^[A-Z2-7]+$")
                .doesNotContain("=");
    }

    @Test
    void followsRfc6238Sha1VectorAfterReducingToSixDigits() {
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

        assertThat(service.codeFor(secret, 1L)).isEqualTo("287082");
        assertThat(service.codeFor(secret, 37_037_036L)).isEqualTo("081804");
    }

    @Test
    void acceptsOnlyCurrentAdjacentTimeStepsAndReturnsMatchedStep() {
        byte[] secret = new byte[32];
        Instant now = Instant.ofEpochSecond(1_710_000_000L);
        long currentStep = now.getEpochSecond() / 30L;

        OptionalLong previous = service.findMatchingTimeStep(
                secret, service.codeFor(secret, currentStep - 1), now);
        OptionalLong current = service.findMatchingTimeStep(
                secret, service.codeFor(secret, currentStep), now);
        OptionalLong next = service.findMatchingTimeStep(
                secret, service.codeFor(secret, currentStep + 1), now);
        OptionalLong outside = service.findMatchingTimeStep(
                secret, service.codeFor(secret, currentStep + 2), now);

        assertThat(previous).hasValue(currentStep - 1);
        assertThat(current).hasValue(currentStep);
        assertThat(next).hasValue(currentStep + 1);
        assertThat(outside).isEmpty();
        assertThat(service.findMatchingTimeStep(secret, "12345", now)).isEmpty();
    }

    @Test
    void buildsStandardProvisioningUriWithoutPadding() {
        byte[] secret = new byte[32];

        String uri = service.provisioningUri("user@example.com", secret);

        assertThat(uri)
                .startsWith("otpauth://totp/AI%20Temperate:user%40example.com?")
                .contains("issuer=AI%20Temperate")
                .contains("algorithm=SHA1")
                .contains("digits=6")
                .contains("period=30")
                .doesNotContain("%3D");
    }

    private static TotpProperties properties() {
        return new TotpProperties(
                "AI Temperate",
                32,
                6,
                Duration.ofSeconds(30),
                1,
                Duration.ofMinutes(10),
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                5,
                new TotpProperties.Encryption("v1", java.util.Base64.getEncoder()
                        .encodeToString(new byte[32])));
    }
}
