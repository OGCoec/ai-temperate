package com.example.temperate.service.user.profile.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 验证用户资料缓存密钥和随机 TTL 只能落在项目允许的安全边界内。
 */
final class UserProfileCachePropertiesTest {

    @Test
    void acceptsCanonicalAes256KeyAndFiveToFifteenMinuteTtl() {
        UserProfileCacheProperties properties = new UserProfileCacheProperties(
                Base64.getEncoder().encodeToString(new byte[32]),
                Duration.ofMinutes(5),
                Duration.ofMinutes(15));

        assertThat(validator().validate(properties)).isEmpty();
        assertThat(properties.toString()).contains("redacted").doesNotContain("AAAA");
    }

    @Test
    void rejectsMissingWrongLengthKeyAndOutOfRangeTtl() {
        assertThat(validator().validate(new UserProfileCacheProperties(
                "",
                Duration.ofMinutes(5),
                Duration.ofMinutes(15)))).isNotEmpty();
        assertThat(validator().validate(new UserProfileCacheProperties(
                Base64.getEncoder().encodeToString(new byte[31]),
                Duration.ofMinutes(5),
                Duration.ofMinutes(15)))).isNotEmpty();
        assertThat(validator().validate(new UserProfileCacheProperties(
                Base64.getEncoder().withoutPadding().encodeToString(new byte[32]),
                Duration.ofMinutes(5),
                Duration.ofMinutes(15)))).isNotEmpty();
        assertThat(validator().validate(new UserProfileCacheProperties(
                Base64.getEncoder().encodeToString(new byte[32]),
                Duration.ofMinutes(4),
                Duration.ofMinutes(16)))).isNotEmpty();
    }

    private static jakarta.validation.Validator validator() {
        return Validation.buildDefaultValidatorFactory().getValidator();
    }
}
