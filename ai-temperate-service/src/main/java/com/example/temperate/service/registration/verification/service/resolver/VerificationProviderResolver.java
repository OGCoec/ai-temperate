package com.example.temperate.service.registration.verification.service.resolver;

import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.enums.VerificationProvider;

/**
 * 根据服务端已知的验证码渠道和目标地址解析供应商。
 *
 * <p>实现不得接受客户端 Bean 名或供应商名；SMS 与 WhatsApp 必须解析真实国际区号，
 * 不能用字符串前缀决定内部供应商。</p>
 */
public interface VerificationProviderResolver {

    /**
     * 解析一次投递或校验应使用的供应商。
     *
     * @param channel 邮箱或短信渠道
     * @param destination 已规范化的邮箱或 E.164 手机号
     * @return 服务端确定的供应商
     */
    default VerificationProvider resolve(
            VerificationChannel channel, String destination) {
        return resolve(channel, VerificationDeliveryMethod.defaultFor(channel), destination);
    }

    /**
     * 根据逻辑验证渠道、受控投递方式和规范化目标解析供应商。
     */
    VerificationProvider resolve(
            VerificationChannel channel,
            VerificationDeliveryMethod deliveryMethod,
            String destination);

    /**
     * 解析当前 RabbitMQ 投递尝试应使用的供应商。
     *
     * <p>邮箱允许按当前 messageId 重新分桶；手机投递按目标号码国家代码和受控投递方式路由。
     * 同一 messageId 被 Broker 原样重新投递时必须得到相同结果，避免一次逻辑尝试内部漂移供应商。</p>
     *
     * @param channel 邮箱或短信渠道
     * @param destination 已规范化的邮箱或 E.164 手机号
     * @param messageId 当前投递尝试的消息标识
     * @return 当前尝试应使用的供应商
     */
    default VerificationProvider resolveDeliveryAttempt(
            VerificationChannel channel,
            String destination,
            String messageId) {
        return resolveDeliveryAttempt(
                channel,
                VerificationDeliveryMethod.defaultFor(channel),
                destination,
                messageId);
    }

    /**
     * 在一次 RabbitMQ 投递尝试中解析供应商，投递方式必须由受保护的服务端消息携带。
     */
    VerificationProvider resolveDeliveryAttempt(
            VerificationChannel channel,
            VerificationDeliveryMethod deliveryMethod,
            String destination,
            String messageId);
}
