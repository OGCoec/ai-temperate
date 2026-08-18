package com.example.temperate.service.user.aiinference.api.impl;

import com.example.temperate.service.user.aiinference.api.ApiInferenceBillingService;
import com.example.temperate.service.user.aiinference.api.ApiInferenceExecutionRequest;
import com.example.temperate.service.user.aiinference.api.ApiInferenceLifecycleService;
import com.example.temperate.service.user.aiinference.api.ApiInferenceLifecycleSession;
import com.example.temperate.service.user.aiinference.api.ApiInferenceLifecycleSession.TerminalState;
import com.example.temperate.service.user.aiinference.api.ApiInferenceReservation;
import com.example.temperate.service.user.aiinference.api.ApiInferenceSettlementPendingException;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUsage;
import com.example.temperate.service.user.aiinference.concurrency.AiInferenceConcurrencyService;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 该实现是来保证“并发准入→短事务预扣→上游调用”的公共顺序，并用请求级 CAS 防止取消、退款和结算重复处理。
 */
@Service
public final class ApiInferenceLifecycleServiceImpl
        implements ApiInferenceLifecycleService {

    private static final Duration RENEW_INTERVAL = Duration.ofSeconds(15);

    private final AiInferenceConcurrencyService concurrencyService;
    private final ApiInferenceBillingService billingService;
    private final Executor finalizerExecutor;
    private final MeterRegistry meterRegistry;

    public ApiInferenceLifecycleServiceImpl(
            AiInferenceConcurrencyService concurrencyService,
            ApiInferenceBillingService billingService,
            @Qualifier("aiConversationFinalizerExecutor") Executor finalizerExecutor,
            MeterRegistry meterRegistry) {
        this.concurrencyService = Objects.requireNonNull(concurrencyService);
        this.billingService = Objects.requireNonNull(billingService);
        this.finalizerExecutor = Objects.requireNonNull(finalizerExecutor);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    @Override
    public ApiInferenceLifecycleSession start(
            ApiKeyPrincipal principal,
            ApiInferenceExecutionRequest request) {
        var acquired = concurrencyService.tryAcquireApiKey(
                principal.loginIdentityId(),
                principal.digestIdentifier(),
                (short) 1);
        if (acquired.result() != AiInferenceConcurrencyService.Result.ACQUIRED) {
            count(request, "admission", acquired.result().name().toLowerCase(Locale.ROOT));
            throw concurrencyException(acquired.result());
        }
        try {
            ApiInferenceReservation reservation = billingService.reserve(principal, request);
            ApiInferenceLifecycleSession session = new ApiInferenceLifecycleSession(
                    acquired.permit(), reservation, request);
            count(request, "reservation", "success");
            return session;
        } catch (ApiChatException exception) {
            concurrencyService.release(acquired.permit());
            count(request, "reservation", exception.code().code());
            throw exception;
        } catch (RuntimeException exception) {
            concurrencyService.release(acquired.permit());
            count(request, "reservation", "unexpected_error");
            throw infrastructure("Billing is temporarily unavailable.");
        }
    }

    @Override
    public <T> Flux<T> withLeaseRenewal(
            Flux<T> source,
            ApiInferenceLifecycleSession session) {
        return source.publish(shared -> {
            // 完成信号必须显式产生一个元素，否则 takeUntilOther 不会停止独立续租时钟。
            Mono<Boolean> completed = shared.then(Mono.just(Boolean.TRUE));
            Flux<T> renewalGuard = Flux.interval(RENEW_INTERVAL)
                    .takeUntilOther(completed)
                    .<T>handle((tick, sink) -> {
                        if (!concurrencyService.renew(session.permit())) {
                            sink.error(infrastructure(
                                    "The concurrency lease could not be renewed."));
                        }
                    });
            return Flux.merge(shared, renewalGuard);
        });
    }

    @Override
    public Mono<Void> settle(
            ApiInferenceLifecycleSession session,
            ApiInferenceUsage usage,
            String finishReason) {
        if (!session.beginTerminal(TerminalState.SETTLING)) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> billingService.settle(
                        session.reservation(), usage, finishReason))
                .subscribeOn(Schedulers.boundedElastic())
                .retry(2)
                .doOnSuccess(ignored -> {
                    session.finalized();
                    count(session, "settlement", "success");
                })
                .onErrorMap(failure -> {
                    session.recoveryPending();
                    count(session, "settlement", "recovery_pending");
                    return new ApiInferenceSettlementPendingException(failure);
                })
                .then();
    }

    @Override
    public Mono<Void> refundSystemFailure(
            ApiInferenceLifecycleSession session,
            String failureCode) {
        if (!session.beginTerminal(TerminalState.REFUNDING)) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> billingService.refundSystemFailure(
                        session.reservation(), failureCode))
                .subscribeOn(Schedulers.boundedElastic())
                .retry(2)
                .doOnSuccess(ignored -> {
                    session.finalized();
                    count(session, "refund", "success");
                })
                // 退款重试耗尽时保留 RESERVED，由恢复任务按安全截止时间收敛，不能覆盖客户端原始错误。
                .onErrorResume(failure -> {
                    session.recoveryPending();
                    count(session, "refund", "recovery_pending");
                    return Mono.empty();
                })
                .then();
    }

    @Override
    public void scheduleCancellation(
            ApiInferenceLifecycleSession session,
            ApiInferenceUsage usage,
            long emittedUtf8Bytes) {
        if (!session.beginTerminal(TerminalState.CANCELLING)) {
            return;
        }
        try {
            finalizerExecutor.execute(() -> runFinalizerWithRetry(session, () -> {
                if (usage != null) {
                    billingService.settle(
                            session.reservation(), usage, "CLIENT_CANCELLED");
                } else {
                    billingService.settleCancellationEstimate(
                            session.reservation(), emittedUtf8Bytes);
                }
            }));
        } catch (RuntimeException schedulingFailure) {
            session.recoveryPending();
            count(session, "cancellation", "executor_rejected");
        }
    }

    @Override
    public void release(ApiInferenceLifecycleSession session) {
        if (session.markReleased()) {
            concurrencyService.release(session.permit());
        }
    }

    private void runFinalizerWithRetry(
            ApiInferenceLifecycleSession session,
            Runnable finalizer) {
        // 三次有界尝试只重放同一幂等数据库终态；全部失败时恢复任务仍以 RESERVED 为事实来源。
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                finalizer.run();
                session.finalized();
                count(session, "cancellation", "success");
                return;
            } catch (RuntimeException ignored) {
                // 只有重试耗尽才改变会话状态，避免中间失败被误报为恢复待处理。
            }
        }
        session.recoveryPending();
        count(session, "cancellation", "recovery_pending");
    }

    private void count(
            ApiInferenceLifecycleSession session,
            String stage,
            String result) {
        count(session.request(), stage, result);
    }

    private void count(
            ApiInferenceExecutionRequest request,
            String stage,
            String result) {
        meterRegistry.counter(
                "api.inference.lifecycle",
                "protocol", request.protocol().name().toLowerCase(Locale.ROOT),
                "mode", request.stream() ? "stream" : "json",
                "stage", stage,
                "result", result).increment();
    }

    private static ApiChatException concurrencyException(
            AiInferenceConcurrencyService.Result result) {
        ApiChatErrorCode code = switch (result) {
            case API_KEY_LIMIT_EXCEEDED -> ApiChatErrorCode.API_KEY_LIMIT_EXCEEDED;
            case ACCOUNT_LIMIT_EXCEEDED -> ApiChatErrorCode.ACCOUNT_LIMIT_EXCEEDED;
            case GLOBAL_LIMIT_EXCEEDED -> ApiChatErrorCode.GLOBAL_LIMIT_EXCEEDED;
            default -> ApiChatErrorCode.INFRASTRUCTURE_UNAVAILABLE;
        };
        return new ApiChatException(code, switch (code) {
            case API_KEY_LIMIT_EXCEEDED ->
                    "The API Key concurrency limit was exceeded.";
            case ACCOUNT_LIMIT_EXCEEDED ->
                    "The account concurrency limit was exceeded.";
            case GLOBAL_LIMIT_EXCEEDED ->
                    "The global concurrency limit was exceeded.";
            default -> "Concurrency control is temporarily unavailable.";
        }, null);
    }

    private static ApiChatException infrastructure(String message) {
        return new ApiChatException(
                ApiChatErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                message,
                null);
    }
}
