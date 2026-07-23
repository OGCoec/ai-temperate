package com.example.temperate.service.registration.verification.delivery.observer;

import com.example.temperate.service.registration.enums.VerificationChannel;

/**
 * 验证码投递失败的可观测性扩展点。
 *
 * <p>用途：将首次有效投递补偿事件写入指标或审计系统，不参与投递成功与否的业务判定。</p>
 */
public interface VerificationDeliveryObserver {

    void deliveryFailed(VerificationChannel channel);
}
