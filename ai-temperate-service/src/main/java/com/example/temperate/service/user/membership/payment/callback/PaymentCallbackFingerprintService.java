package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.common.security.hmac.HmacIdentifier;

/**
 * 该服务是来为模拟支付回调生成协议固定的 HMAC 幂等指纹和移除敏感字段后的排序载荷摘要。
 */
public interface PaymentCallbackFingerprintService {

    HmacIdentifier fingerprint(SimulatedLiuhaoCallbackCommand command);

    HmacIdentifier providerTradeFingerprint(SimulatedLiuhaoCallbackCommand command);

    String payloadDigest(SimulatedLiuhaoCallbackCommand command);
}
