package com.example.temperate.service.registration.verification.delivery.status;

import java.time.Duration;
import java.time.Instant;

/**
 * 保存 Twilio Message SID 的脱敏状态索引，供异步状态回调更新可观测结果。
 *
 * <p>该接口只维护 Provider 状态，不创建新消息，也不改变验证码发送重试决策。
 */
public interface TwilioWhatsAppStatusStore {

    void recordAccepted(
            String providerMessageId,
            String operationId,
            String providerStatus,
            Instant acceptedAt,
            Duration ttl);

    boolean recordCallback(
            String providerMessageId,
            String providerStatus,
            String providerErrorCode,
            Instant receivedAt,
            Duration ttl);
}
