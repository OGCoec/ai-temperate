package com.example.temperate.common.validation.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 验证邮箱输入的规范化规则、格式边界和非法字符拒绝行为。
 */
class EmailAddressNormalizerTest {

    @Test
    void normalizesCaseAndSurroundingAsciiSpacesWhileKeepingCommonLocalCharacters() {
        assertThat(EmailAddressNormalizer.normalize(
                "  Person.Name+Alerts_Ops@Example.TEST  "))
                .isEqualTo("person.name+alerts_ops@example.test");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            ".person@example.test",
            "person.@example.test",
            "person..name@example.test",
            "person@-example.test",
            "person@example-.test",
            "person@example..test",
            "person@",
            "@example.test",
            "person@@example.test",
            "person\t@example.test",
            "person@example.test\n"
    })
    void rejectsMalformedWhitespaceAndControlCharacterInputs(String value) {
        assertThatThrownBy(() -> EmailAddressNormalizer.normalize(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email address is invalid.")
                .hasMessageNotContaining(value);
    }

    @Test
    void rejectsAddressesLongerThanTwoHundredFiftyFourCharacters() {
        String value = "a".repeat(245) + "@example.test";

        assertThat(value).hasSize(258);
        assertThatThrownBy(() -> EmailAddressNormalizer.normalize(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email address is invalid.");
    }
}
