package com.example.temperate.service.registration.verification.service.resolver.impl;

import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.enums.VerificationProvider;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.verification.service.resolver.VerificationProviderResolver;
import com.example.temperate.service.registration.verification.service.resolver.VerificationDeliveryMethodPolicy;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 使用消息稳定分桶选择邮箱供应商，并使用 libphonenumber 按真实国家代码选择手机号投递供应商。
 *
 * <p>校验入口仍选择 Gmail 作为共享 Redis 校验器的稳定代理；实际邮件投递按当前 messageId 在 Gmail
 * 和 Microsoft Graph 之间 50/50 分桶。短信国家代码为 86 时选择阿里云，其余有效国际号码选择
 * Twilio SMS；非中国国际号码选择 WhatsApp 时使用 Twilio Programmable Messaging。
 * 无效号码、缺少国际区号和中国号码选择 WhatsApp 时会返回受控业务错误。</p>
 */
@Component
public final class LibphonenumberVerificationProviderResolver
        implements VerificationProviderResolver {

    private final PhoneNumberUtil phoneNumberUtil;

    public LibphonenumberVerificationProviderResolver() {
        this(PhoneNumberUtil.getInstance());
    }

    LibphonenumberVerificationProviderResolver(PhoneNumberUtil phoneNumberUtil) {
        this.phoneNumberUtil =
                Objects.requireNonNull(phoneNumberUtil, "phoneNumberUtil must not be null");
    }

    @Override
    public VerificationProvider resolve(
            VerificationChannel channel,
            VerificationDeliveryMethod deliveryMethod,
            String destination) {
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(deliveryMethod, "deliveryMethod must not be null");
        if (destination == null || destination.isBlank()) {
            throw unsupported();
        }
        VerificationDeliveryMethodPolicy.requireSupported(channel, deliveryMethod, destination);
        if (channel == VerificationChannel.EMAIL) {
            return VerificationProvider.GMAIL;
        }
        if (channel != VerificationChannel.SMS
                || deliveryMethod == VerificationDeliveryMethod.EMAIL
                || !destination.startsWith("+")) {
            throw unsupported();
        }
        try {
            var parsedNumber = phoneNumberUtil.parse(destination, "ZZ");
            if (!phoneNumberUtil.isValidNumber(parsedNumber)) {
                throw unsupported();
            }
            if (deliveryMethod == VerificationDeliveryMethod.WHATSAPP) {
                if (parsedNumber.getCountryCode() == 86) {
                    throw unsupported();
                }
                return VerificationProvider.TWILIO_WHATSAPP;
            }
            return parsedNumber.getCountryCode() == 86
                    ? VerificationProvider.ALIYUN_SMS
                    : VerificationProvider.TWILIO_SMS;
        } catch (NumberParseException exception) {
            throw new RegistrationException(
                    RegistrationErrorCode.VERIFICATION_CHANNEL_UNSUPPORTED,
                    "Unsupported verification destination.",
                    exception);
        }
    }

    @Override
    public VerificationProvider resolveDeliveryAttempt(
            VerificationChannel channel,
            VerificationDeliveryMethod deliveryMethod,
            String destination,
            String messageId) {
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(deliveryMethod, "deliveryMethod must not be null");
        if (messageId == null || messageId.isBlank()) {
            throw unsupported();
        }
        if (channel == VerificationChannel.EMAIL) {
            if (deliveryMethod != VerificationDeliveryMethod.EMAIL
                    || destination == null
                    || destination.isBlank()) {
                throw unsupported();
            }
            // messageId 由发布方随机生成；稳定哈希让新重试重新近似均匀分桶，同时保持同一消息重投结果不变。
            int bucket = Math.floorMod(messageId.hashCode(), 2);
            return bucket == 0
                    ? VerificationProvider.GMAIL
                    : VerificationProvider.MICROSOFT_GRAPH;
        }
        return resolve(channel, deliveryMethod, destination);
    }

    private static RegistrationException unsupported() {
        return new RegistrationException(
                RegistrationErrorCode.VERIFICATION_CHANNEL_UNSUPPORTED,
                "Unsupported verification destination.");
    }
}
