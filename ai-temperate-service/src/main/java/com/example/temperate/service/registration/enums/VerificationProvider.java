package com.example.temperate.service.registration.enums;

/**
 * 表示六位数验证码投递使用的稳定供应商类型，并声明供应商所属验证渠道和实际投递方式。
 *
 * <p>该枚举只用于服务端注册和路由，客户端不得直接提交供应商类型或据此选择 Spring Bean。</p>
 */
public enum VerificationProvider {

    GMAIL(VerificationChannel.EMAIL, VerificationDeliveryMethod.EMAIL),
    MICROSOFT_GRAPH(VerificationChannel.EMAIL, VerificationDeliveryMethod.EMAIL),
    ALIYUN_SMS(VerificationChannel.SMS, VerificationDeliveryMethod.SMS),
    TWILIO_SMS(VerificationChannel.SMS, VerificationDeliveryMethod.SMS),
    TWILIO_WHATSAPP(VerificationChannel.SMS, VerificationDeliveryMethod.WHATSAPP);

    private final VerificationChannel channel;
    private final VerificationDeliveryMethod deliveryMethod;

    VerificationProvider(
            VerificationChannel channel,
            VerificationDeliveryMethod deliveryMethod) {
        this.channel = channel;
        this.deliveryMethod = deliveryMethod;
    }

    public VerificationChannel channel() {
        return channel;
    }

    public VerificationDeliveryMethod deliveryMethod() {
        return deliveryMethod;
    }
}
