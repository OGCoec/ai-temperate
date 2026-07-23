package com.example.temperate.service.registration.enums;

import java.util.Objects;

/**
 * 表示验证码从供应商送达用户时采用的具体投递方式，并与邮箱或手机号验证因子保持分离。
 *
 * <p>SMS 与 WHATSAPP 都服务于同一个手机号验证因子，因此共享验证码摘要、发送频率、失败次数和验证状态；
 * 客户端只能提交该枚举，不能直接选择内部供应商或 Spring Bean。</p>
 */
public enum VerificationDeliveryMethod {
    EMAIL,
    SMS,
    WHATSAPP;

    /**
     * 根据逻辑验证渠道返回兼容旧客户端和旧消息的默认投递方式。
     */
    public static VerificationDeliveryMethod defaultFor(VerificationChannel channel) {
        Objects.requireNonNull(channel, "channel must not be null");
        return channel == VerificationChannel.EMAIL ? EMAIL : SMS;
    }
}
