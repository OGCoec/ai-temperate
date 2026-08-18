package com.example.temperate.service.user.aiinference.api;

import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;

/**
 * 该服务是来在 PostgreSQL 短事务中统一处理公开推理的预扣、实际结算、取消估算和系统失败退款，网络调用不属于事务边界。
 */
public interface ApiInferenceBillingService {

    ApiInferenceReservation reserve(
            ApiKeyPrincipal principal,
            ApiInferenceExecutionRequest request);

    void settle(
            ApiInferenceReservation reservation,
            ApiInferenceUsage usage,
            String finishReason);

    void settleCancellationEstimate(
            ApiInferenceReservation reservation,
            long emittedUtf8Bytes);

    void refundSystemFailure(
            ApiInferenceReservation reservation,
            String failureCode);
}
