package com.example.temperate.service.registration.verification.delivery.rabbit;

/**
 * 区分验证码投递消息所属的业务状态机。
 */
public enum VerificationDeliveryFlowKind {
    REGISTRATION,
    LOGIN,
    PASSWORD_RESET
}
