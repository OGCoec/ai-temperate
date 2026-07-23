package com.example.temperate.service.auth.login.component.normalizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.auth.login.dto.command.LoginCommand;
import com.example.temperate.service.auth.login.dto.internal.NormalizedLoginInput;
import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.enums.LoginIdentifierType;
import com.example.temperate.service.auth.login.exception.LoginException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证登录输入的身份、密码、设备和 IPv4/IPv6 规范化边界。
 */
class LoginInputNormalizerTest {

    private static final String DEVICE_ID =
            "install_A2345678901234567890123456789012345678";
    private static final String CLIENT_IP = "203.0.113.10";

    private LoginInputNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new LoginInputNormalizer();
    }

    @Test
    void normalizesEmailCaseAndSurroundingWhitespace() {
        NormalizedLoginInput normalized = normalizer.normalize(command(
                "  Person.Name@Example.TEST  ", "password"));

        assertThat(normalized.getIdentifierType()).isEqualTo(LoginIdentifierType.EMAIL);
        assertThat(normalized.getIdentifier()).isEqualTo("person.name@example.test");
        assertThat(normalized.getRawPassword()).isEqualTo("password");
        assertThat(normalized.getDeviceInstallationId()).isEqualTo(DEVICE_ID);
        assertThat(normalized.getCanonicalClientIp()).isEqualTo(CLIENT_IP);
    }

    @Test
    void acceptsCommonPlusAddressingInEmailLocalPart() {
        NormalizedLoginInput normalized = normalizer.normalize(command(
                "Person.Name+Alerts@Example.TEST", "password"));

        assertThat(normalized.getIdentifierType()).isEqualTo(LoginIdentifierType.EMAIL);
        assertThat(normalized.getIdentifier()).isEqualTo(
                "person.name+alerts@example.test");
    }

    @Test
    void acceptsOnlyAlreadyCanonicalE164PhoneIdentifiers() {
        NormalizedLoginInput normalized = normalizer.normalize(command(
                "+13125550100", "password"));

        assertThat(normalized.getIdentifierType()).isEqualTo(LoginIdentifierType.PHONE);
        assertThat(normalized.getIdentifier()).isEqualTo("+13125550100");

        assertInvalid(command("13125550100", "password"));
        assertInvalid(command("+1 312 555 0100", "password"));
        assertInvalid(command(" +13125550100 ", "password"));
        assertInvalid(command("+01234567890", "password"));
    }

    @Test
    void rejectsMalformedEmailAndMissingCommandFields() {
        assertInvalid(command("person.example.test", "password"));
        assertInvalid(command("person@@example.test", "password"));
        assertInvalid(command("person @example.test", "password"));
        assertInvalid(command(".person@example.test", "password"));
        assertInvalid(command("person.@example.test", "password"));
        assertInvalid(command("person..name@example.test", "password"));
        assertInvalid(command("person@-example.test", "password"));
        assertInvalid(command("person@example-.test", "password"));
        assertInvalid(command("person@example..test", "password"));
        assertInvalid(command("person@", "password"));
        assertInvalid(command("@example.test", "password"));
        assertInvalid(command("person\t@example.test", "password"));
        assertInvalid(command("person@example.test\n", "password"));
        assertInvalid(new LoginCommand(null, "password", DEVICE_ID, CLIENT_IP));
        assertInvalid(null);
    }

    @Test
    void enforcesPasswordUtf8ByteBoundaries() {
        String eightBytes = "12345678";
        String seventyTwoBytes = "a".repeat(72);

        assertThat(eightBytes.getBytes(StandardCharsets.UTF_8)).hasSize(8);
        assertThat(seventyTwoBytes.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        assertThat(normalizer.normalize(command("person@example.test", eightBytes))
                .getRawPassword()).isEqualTo(eightBytes);
        assertThat(normalizer.normalize(command("person@example.test", seventyTwoBytes))
                .getRawPassword()).isEqualTo(seventyTwoBytes);

        assertInvalid(command("person@example.test", "a".repeat(7)));
        assertInvalid(command("person@example.test", "a".repeat(73)));
        assertInvalid(command("person@example.test", "密".repeat(25)));
    }

    @Test
    void requiresStrictDeviceInstallationIdAndCanonicalClientIp() {
        assertThat(normalizer.normalizeDeviceInstallationId(DEVICE_ID)).isEqualTo(DEVICE_ID);
        assertThat(normalizer.normalize(commandWithIp("2001:db8::1"))
                .getCanonicalClientIp()).isEqualTo("2001:db8::1");

        assertInvalid(new LoginCommand(
                "person@example.test", "password", "short", CLIENT_IP));
        assertInvalid(new LoginCommand(
                "person@example.test", "password", DEVICE_ID, "example.test"));
        assertInvalid(new LoginCommand(
                "person@example.test", "password", DEVICE_ID, "203.000.113.10"));
        assertInvalid(new LoginCommand(
                "person@example.test", "password", DEVICE_ID, "2001:0db8::1"));
        assertInvalid(new LoginCommand(
                "person@example.test", "password", DEVICE_ID, "2001:DB8::1"));
    }

    private static LoginCommand command(String identifier, String password) {
        return new LoginCommand(identifier, password, DEVICE_ID, CLIENT_IP);
    }

    private static LoginCommand commandWithIp(String clientIp) {
        return new LoginCommand("person@example.test", "password", DEVICE_ID, clientIp);
    }

    private static void assertInvalid(LoginCommand command) {
        assertThatThrownBy(() -> new LoginInputNormalizer().normalize(command))
                .isInstanceOfSatisfying(LoginException.class, exception ->
                        assertThat(exception.code()).isEqualTo(LoginErrorCode.INVALID_INPUT));
    }
}
