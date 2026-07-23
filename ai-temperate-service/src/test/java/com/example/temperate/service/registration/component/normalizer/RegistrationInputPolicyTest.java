package com.example.temperate.service.registration.component.normalizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 验证注册邮箱、手机号及设备输入边界规范化规则的测试。
 */
class RegistrationInputPolicyTest {

    private static final PhoneNumberUtil PHONE_NUMBER_UTIL = PhoneNumberUtil.getInstance();

    private final RegistrationInputNormalizer normalizer = new RegistrationInputNormalizer();

    @Test
    void trimsAndLowerCasesEmail() {
        assertThat(normalizer.normalizeEmail("  Alice.Example@Example.COM  "))
                .isEqualTo("alice.example@example.com");
        assertThat(normalizer.normalizeEmail("Alice.Example+Alerts@Example.COM"))
                .isEqualTo("alice.example+alerts@example.com");
    }

    @Test
    void rejectsMalformedEmailStructureWithoutLeakingInput() {
        assertInvalidEmail(".person@example.test");
        assertInvalidEmail("person.@example.test");
        assertInvalidEmail("person..name@example.test");
        assertInvalidEmail("person@-example.test");
        assertInvalidEmail("person@example-.test");
        assertInvalidEmail("person@example..test");
        assertInvalidEmail("person@");
        assertInvalidEmail("@example.test");
        assertInvalidEmail("person\t@example.test");
        assertInvalidEmail("person@example.test\n");
    }

    @Test
    void parsesCountryAndNationalNumberAsE164() {
        assertThat(normalizer.normalizePhone("US", "3125550100"))
                .isEqualTo("+13125550100");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abc123",
            "中文123",
            "123-456",
            "(415)5552671",
            "+12+3",
            "++123",
            "+"
    })
    void rejectsMalformedPhoneBeforeCallingLibphonenumber(String value) {
        PhoneNumberUtil phoneNumberUtil = mock(PhoneNumberUtil.class);
        RegistrationInputNormalizer guardedNormalizer =
                new RegistrationInputNormalizer(phoneNumberUtil);

        assertThatThrownBy(() -> guardedNormalizer.normalizePhone("US", value))
                .isInstanceOfSatisfying(RegistrationException.class, exception ->
                        assertThat(exception.code()).isEqualTo(RegistrationErrorCode.INVALID_INPUT));
        verifyNoInteractions(phoneNumberUtil);
    }

    @Test
    void acceptsInternationalPhoneInputWhenItBelongsToSelectedRegion() {
        assertThat(normalizer.normalizePhone("US", "+14155552671"))
                .isEqualTo("+14155552671");
    }

    @Test
    void rejectsInternationalPhoneInputWhenItBelongsToAnotherRegion() {
        assertThatThrownBy(() -> normalizer.normalizePhone("CN", "+14155552671"))
                .isInstanceOfSatisfying(RegistrationException.class, exception ->
                        assertThat(exception.code()).isEqualTo(RegistrationErrorCode.INVALID_INPUT));
    }

    @ParameterizedTest
    @EnumSource(value = PhoneNumberType.class, names = {"MOBILE", "FIXED_LINE_OR_MOBILE"})
    void acceptsMobileCapablePhoneNumberTypes(PhoneNumberType type) {
        TypedPhoneExample example = exampleFor(type);

        assertThat(normalizer.normalizePhone(example.region(), example.nationalNumber()))
                .isEqualTo(PHONE_NUMBER_UTIL.format(example.number(), PhoneNumberFormat.E164));
    }

    @ParameterizedTest
    @EnumSource(
            value = PhoneNumberType.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = {"MOBILE", "FIXED_LINE_OR_MOBILE", "UNKNOWN"})
    void rejectsValidButNonMobilePhoneNumberTypes(PhoneNumberType type) {
        TypedPhoneExample example = exampleFor(type);

        assertThatThrownBy(() ->
                normalizer.normalizePhone(example.region(), example.nationalNumber()))
                .isInstanceOfSatisfying(RegistrationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(RegistrationErrorCode.INVALID_INPUT));
    }

    @Test
    void rejectsInvalidPhoneWithoutLeakingLibraryErrors() {
        assertThatThrownBy(() -> normalizer.normalizePhone("US", "123"))
                .isInstanceOfSatisfying(RegistrationException.class, exception ->
                        assertThat(exception.code()).isEqualTo(RegistrationErrorCode.INVALID_INPUT));
    }


    private void assertInvalidEmail(String value) {
        assertThatThrownBy(() -> normalizer.normalizeEmail(value))
                .isInstanceOfSatisfying(RegistrationException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(RegistrationErrorCode.INVALID_INPUT);
                    assertThat(exception.getMessage()).doesNotContain(value);
                });
    }

    private static TypedPhoneExample exampleFor(PhoneNumberType expectedType) {
        return PHONE_NUMBER_UTIL.getSupportedRegions().stream()
                .sorted(Comparator.naturalOrder())
                .map(region -> exampleFor(region, expectedType))
                .filter(example -> example != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No libphonenumber example for type " + expectedType));
    }

    private static TypedPhoneExample exampleFor(
            String region,
            PhoneNumberType expectedType) {
        PhoneNumber number = PHONE_NUMBER_UTIL.getExampleNumberForType(region, expectedType);
        if (number == null
                || !PHONE_NUMBER_UTIL.isValidNumberForRegion(number, region)
                || PHONE_NUMBER_UTIL.getNumberType(number) != expectedType) {
            return null;
        }
        return new TypedPhoneExample(
                region,
                PHONE_NUMBER_UTIL.getNationalSignificantNumber(number),
                number);
    }

    private record TypedPhoneExample(
            String region,
            String nationalNumber,
            PhoneNumber number) {
    }
}
