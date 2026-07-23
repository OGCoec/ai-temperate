package com.example.temperate.service.registration.component.normalizer;

import com.example.temperate.common.validation.email.EmailAddressNormalizer;
import com.example.temperate.common.validation.phone.PhoneNumberInputPolicy;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 将注册邮箱和手机号输入校验并规范化为身份查询与持久化使用的统一格式。
 *
 * <p>邮箱委派统一邮箱规范化器，手机号使用指定国家代码解析为 E.164；该组件不查询账号存在性，也不负责验证码校验。</p>
 */
@Component
public final class RegistrationInputNormalizer {

    private static final Set<PhoneNumberType> ALLOWED_PHONE_NUMBER_TYPES = Set.of(
            PhoneNumberType.MOBILE,
            PhoneNumberType.FIXED_LINE_OR_MOBILE);

    private final PhoneNumberUtil phoneNumberUtil;

    public RegistrationInputNormalizer() {
        this(PhoneNumberUtil.getInstance());
    }

    RegistrationInputNormalizer(PhoneNumberUtil phoneNumberUtil) {
        this.phoneNumberUtil = phoneNumberUtil;
    }

    public String normalizeEmail(String email) {
        try {
            return EmailAddressNormalizer.normalize(email);
        } catch (IllegalArgumentException exception) {
            throw invalidInput("Email is invalid.");
        }
    }

    public String normalizePhone(String countryIso2, String nationalNumber) {
        if (countryIso2 == null || nationalNumber == null) {
            throw invalidInput("Phone country and national number are required.");
        }
        // 使用 Locale.ROOT 固定国家代码归一化结果，避免部署环境区域设置改变电话号码解析语义。
        String region = countryIso2.trim().toUpperCase(Locale.ROOT);
        if (!region.matches("^[A-Z]{2}$")
                || !PhoneNumberInputPolicy.isBasicPhoneInput(nationalNumber)) {
            throw invalidInput("Phone number is invalid.");
        }
        try {
            // 基础字符防线通过后才进入 libphonenumber，避免英文、中文或任意标点被当作可提取文本解析。
            String parseRegion = nationalNumber.startsWith("+") ? "ZZ" : region;
            var parsed = phoneNumberUtil.parse(nationalNumber, parseRegion);
            if (!phoneNumberUtil.isValidNumberForRegion(parsed, region)) {
                throw invalidInput("Phone number is invalid.");
            }
            PhoneNumberType numberType = phoneNumberUtil.getNumberType(parsed);
            if (!ALLOWED_PHONE_NUMBER_TYPES.contains(numberType)) {
                throw invalidInput("Phone number must be a supported mobile number.");
            }
            return phoneNumberUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException exception) {
            throw invalidInput("Phone number is invalid.");
        }
    }

    private static RegistrationException invalidInput(String message) {
        return new RegistrationException(RegistrationErrorCode.INVALID_INPUT, message);
    }
}
