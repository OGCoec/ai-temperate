package com.example.temperate.service.user.aiinference.api;

import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 该服务是来统一编排公开 Chat 与 Responses 的并发准入、预扣、租约续期和互斥终态，不解析任何具体上游协议帧。
 */
public interface ApiInferenceLifecycleService {

    ApiInferenceLifecycleSession start(
            ApiKeyPrincipal principal,
            ApiInferenceExecutionRequest request);

    <T> Flux<T> withLeaseRenewal(
            Flux<T> source,
            ApiInferenceLifecycleSession session);

    Mono<Void> settle(
            ApiInferenceLifecycleSession session,
            ApiInferenceUsage usage,
            String finishReason);

    Mono<Void> refundSystemFailure(
            ApiInferenceLifecycleSession session,
            String failureCode);

    void scheduleCancellation(
            ApiInferenceLifecycleSession session,
            ApiInferenceUsage usage,
            long emittedUtf8Bytes);

    void release(ApiInferenceLifecycleSession session);
}
