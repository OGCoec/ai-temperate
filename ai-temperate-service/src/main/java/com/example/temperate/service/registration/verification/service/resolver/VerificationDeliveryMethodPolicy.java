package com.example.temperate.service.registration.verification.service.resolver;

import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 集中校验客户端选择的验证码投递方式与服务端逻辑渠道、目标国家之间的固定兼容规则。
 *
 * <p>该策略只接受有限枚举，并使用 libphonenumber 解析真实国家代码；它不选择供应商，也不会把客户端值
 * 当作 Bean 名称，从而可在发码状态写入 Redis 之前同步拒绝不支持的组合。</p>
 */
public final class VerificationDeliveryMethodPolicy {

    private static final PhoneNumberUtil PHONE_NUMBER_UTIL = PhoneNumberUtil.getInstance();
    private static final Pattern E164_PATTERN = Pattern.compile("^\\+[1-9][0-9]{1,14}$");

    private VerificationDeliveryMethodPolicy() {
    }

    /**
     * 校验逻辑渠道、受控投递方式和规范化目标的组合，并在任何验证码或限流状态写入前拒绝不支持的请求。
     */
    public static void requireSupported(
            VerificationChannel channel,
            VerificationDeliveryMethod deliveryMethod,
            String destination) {
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(deliveryMethod, "deliveryMethod must not be null");
        if (channel == VerificationChannel.EMAIL) {
            if (deliveryMethod != VerificationDeliveryMethod.EMAIL) {
                throw unsupported();
            }
            return;
        }
        if (deliveryMethod == VerificationDeliveryMethod.EMAIL) {
            throw unsupported();
        }
        if (destination == null || !E164_PATTERN.matcher(destination).matches()) {
            throw unsupported();
        }
        try {
            var parsed = PHONE_NUMBER_UTIL.parse(destination, "ZZ");
            if (!PHONE_NUMBER_UTIL.isValidNumber(parsed)
                    || (deliveryMethod == VerificationDeliveryMethod.WHATSAPP
                            && parsed.getCountryCode() == 86)) {
                throw unsupported();
            }
        } catch (NumberParseException exception) {
            throw new RegistrationException(
                    RegistrationErrorCode.VERIFICATION_CHANNEL_UNSUPPORTED,
                    "Unsupported verification delivery method.",
                    exception);
        }
    }

    private static RegistrationException unsupported() {
        return new RegistrationException(
                RegistrationErrorCode.VERIFICATION_CHANNEL_UNSUPPORTED,
                "Unsupported verification delivery method.");
    }
}
