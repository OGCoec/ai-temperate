package com.example.temperate.service.registration.verification.delivery.operation;

import cn.hutool.core.lang.id.NanoId;
import org.springframework.stereotype.Component;

/**
 * 为每一次验证码投递操作生成临时原始随机编号。
 *
 * <p>生成值只作为 HMAC 输入使用，不能写入 Redis、RabbitMQ 或日志；持久化边界只能保存 protector 计算后的
 * Base64URL HMAC 摘要。</p>
 */
@Component
public final class VerificationDeliveryOperationIdGenerator {

    private static final int OPERATION_ID_LENGTH = 38;

    public String generateRawOperationId() {
        return NanoId.randomNanoId(OPERATION_ID_LENGTH);
    }
}
