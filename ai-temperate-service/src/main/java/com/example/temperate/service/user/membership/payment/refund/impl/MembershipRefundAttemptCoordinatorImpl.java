package com.example.temperate.service.user.membership.payment.refund.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.model.user.membership.payment.MembershipOrderEntitlementResolution;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.MembershipPaymentCallbackResolution;
import com.example.temperate.model.user.membership.payment.MembershipPaymentRefundTerminalFact;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentRefundService;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackPersistenceService;
import com.example.temperate.service.user.membership.payment.callback.PaymentRefundAttemptOutcome;
import com.example.temperate.service.user.membership.payment.callback.PaymentRefundAttemptResult;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentLifecycleDiagnostics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTraceContext;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderReference;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipRefundMessagePublisher;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipRefundRetryMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipRefundTerminalFailureMessage;
import com.example.temperate.service.user.membership.payment.refund.MembershipRefundAttemptCoordinator;
import com.example.temperate.service.user.membership.payment.refund.PaymentRefundCoordinationAction;
import com.example.temperate.service.user.membership.payment.refund.PaymentRefundCoordinationDecision;
import com.example.temperate.service.user.membership.payment.refund.PaymentRefundCoordinationStore;
import com.example.temperate.service.user.membership.payment.refund.PaymentRefundTerminalOutcome;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来把退款调用、Redis 尝试状态和 Rabbit Confirm 串成可恢复顺序，明确失败绝不产生下一次退款请求。
 *
 * <p>外部调用前必须已经存在 PostgreSQL REFUND_REQUIRED 事实；RabbitMQ 是 At-Least-Once，重复投递由
 * callbackId、attemptNo、messageId 的 Lua 状态和固定退款号共同收敛，不能描述为 Exactly Once。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipRefundAttemptCoordinatorImpl
        implements MembershipRefundAttemptCoordinator {

    private static final int MAX_ATTEMPTS = 6;

    private final PaymentRefundCoordinationStore store;
    private final MembershipPaymentRefundService refundService;
    private final MembershipRefundMessagePublisher publisher;
    private final PaymentCallbackPersistenceService persistenceService;
    private final HybridBase64UrlCodec idCodec;
    private final List<Long> retryDelaysMillis;

    public MembershipRefundAttemptCoordinatorImpl(
            PaymentRefundCoordinationStore store,
            MembershipPaymentRefundService refundService,
            MembershipRefundMessagePublisher publisher,
            PaymentCallbackPersistenceService persistenceService,
            HybridBase64UrlCodec idCodec,
            MembershipPaymentProperties properties) {
        this.store = Objects.requireNonNull(store);
        this.refundService = Objects.requireNonNull(refundService);
        this.publisher = Objects.requireNonNull(publisher);
        this.persistenceService = Objects.requireNonNull(persistenceService);
        this.idCodec = Objects.requireNonNull(idCodec);
        this.retryDelaysMillis = List.copyOf(
                Objects.requireNonNull(properties).rabbit().refundRetryDelaysMillis());
        if (retryDelaysMillis.size() != MAX_ATTEMPTS - 1) {
            throw new IllegalArgumentException("Membership refund retry plan is invalid.");
        }
    }

    @Override
    public void processInitial(String callbackId, PaymentRefundCommand command) {
        PaymentRefundCommand validCommand = Objects.requireNonNull(command);
        PaymentProviderType provider = PaymentProviderReference.resolveTrade(
                validCommand.providerTradeNo());
        PaymentRefundCoordinationDecision decision = store.beginInitial(callbackId);
        processDecision(callbackId, validCommand, provider, decision);
    }

    @Override
    public void processRetry(
            MembershipPaymentRabbitEnvelope<MembershipRefundRetryMessage> envelope) {
        MembershipPaymentRabbitEnvelope<MembershipRefundRetryMessage> valid =
                Objects.requireNonNull(envelope);
        if (!MembershipPaymentRabbitNames.REFUND_RETRY_EVENT.equals(valid.eventType())) {
            throw new IllegalArgumentException("Membership refund retry event is invalid.");
        }
        MembershipRefundRetryMessage message = valid.payload();
        RefundFactCheck factCheck = loadRefundFact(message.callbackId());
        PaymentRefundCoordinationDecision decision = store.claimRetry(
                message.callbackId(), message.attemptNo(), valid.messageId());
        if (decision.action() == PaymentRefundCoordinationAction.STALE_MESSAGE) {
            return;
        }
        if (decision.action() == PaymentRefundCoordinationAction.MESSAGE_NOT_READY) {
            throw unavailable("Refund retry message arrived before coordination commit.");
        }
        PaymentProviderType provider = factCheck.provider();
        if (decision.action() == PaymentRefundCoordinationAction.PUBLISH_RETRY) {
            publishExistingRetry(message.callbackId(), decision);
            return;
        }
        if (decision.action() == PaymentRefundCoordinationAction.PUBLISH_TERMINAL) {
            publishExistingTerminal(message.callbackId(), provider, decision);
            return;
        }
        if (decision.action()
                == PaymentRefundCoordinationAction.ATTEMPT_OUTCOME_UNKNOWN) {
            publishNewTerminal(
                    message.callbackId(),
                    message.attemptNo(),
                    provider,
                    PaymentRefundTerminalOutcome.ATTEMPT_OUTCOME_UNKNOWN,
                    "ATTEMPT_OUTCOME_UNKNOWN");
            return;
        }
        if (decision.action() != PaymentRefundCoordinationAction.ATTEMPT_PROVIDER) {
            throw unavailable("Refund retry coordination action is invalid.");
        }
        if (!factCheck.verified()) {
            publishNewTerminal(
                    message.callbackId(),
                    message.attemptNo(),
                    provider,
                    PaymentRefundTerminalOutcome.ATTEMPT_OUTCOME_UNKNOWN,
                    factCheck.safeReason());
            return;
        }
        handleAttempt(
                message.callbackId(),
                factCheck.command(),
                provider,
                message.attemptNo());
    }

    private void processDecision(
            String callbackId,
            PaymentRefundCommand command,
            PaymentProviderType provider,
            PaymentRefundCoordinationDecision decision) {
        switch (decision.action()) {
            case ATTEMPT_PROVIDER -> handleAttempt(
                    callbackId, command, provider, decision.attemptNo());
            case PUBLISH_RETRY -> publishExistingRetry(callbackId, decision);
            case PUBLISH_TERMINAL -> publishExistingTerminal(callbackId, provider, decision);
            case COMPLETE_COORDINATED -> {
                // 原 callback 可能在 Confirm 后、complete 前崩溃；协调终态允许只补做 callback complete。
            }
            case ATTEMPT_OUTCOME_UNKNOWN -> publishNewTerminal(
                    callbackId,
                    decision.attemptNo(),
                    provider,
                    PaymentRefundTerminalOutcome.ATTEMPT_OUTCOME_UNKNOWN,
                    "ATTEMPT_OUTCOME_UNKNOWN");
            case MESSAGE_NOT_READY, STALE_MESSAGE ->
                    throw unavailable("Initial refund coordination action is invalid.");
        }
    }

    private void handleAttempt(
            String callbackId,
            PaymentRefundCommand command,
            PaymentProviderType provider,
            int attemptNo) {
        PaymentRefundAttemptResult result = refundService.refund(command, attemptNo);
        PaymentRefundAttemptResult classified = result == null
                ? new PaymentRefundAttemptResult(
                        PaymentRefundAttemptOutcome.EXPLICIT_FAILURE,
                        "ATTEMPT_RESULT_MISSING",
                        provider,
                        attemptNo)
                : result;
        if (classified.provider() != provider || classified.attemptNo() != attemptNo) {
            classified = new PaymentRefundAttemptResult(
                    PaymentRefundAttemptOutcome.EXPLICIT_FAILURE,
                    "ATTEMPT_RESULT_MISMATCH",
                    provider,
                    attemptNo);
        }
        boolean retryAllowed = classified.outcome() == PaymentRefundAttemptOutcome.TIMED_OUT
                && attemptNo < MAX_ATTEMPTS;
        MembershipPaymentLifecycleDiagnostics.refundAttempt(
                provider,
                attemptNo,
                classified.outcome().name().toLowerCase(java.util.Locale.ROOT),
                classified.safeReason(),
                retryAllowed,
                MembershipPaymentTraceContext.currentTraceId());
        switch (classified.outcome()) {
            case SUCCEEDED -> requireTransition(
                    store.markSucceeded(callbackId, attemptNo),
                    "Refund success coordination failed.");
            case EXPLICIT_FAILURE -> publishNewTerminal(
                    callbackId,
                    attemptNo,
                    provider,
                    PaymentRefundTerminalOutcome.EXPLICIT_FAILURE,
                    classified.safeReason());
            case TIMED_OUT -> {
                if (attemptNo >= MAX_ATTEMPTS) {
                    publishNewTerminal(
                            callbackId,
                            attemptNo,
                            provider,
                            PaymentRefundTerminalOutcome.TIMEOUT_EXHAUSTED,
                            "TIMEOUT_EXHAUSTED");
                } else {
                    prepareAndPublishRetry(
                            callbackId, attemptNo, classified.safeReason());
                }
            }
        }
    }

    private void prepareAndPublishRetry(
            String callbackId, int attemptNo, String safeReason) {
        int nextAttemptNo = attemptNo + 1;
        String messageId = publisher.newMessageId();
        requireTransition(
                store.prepareRetry(
                        callbackId,
                        attemptNo,
                        messageId,
                        nextAttemptNo,
                        safeReason),
                "Refund retry preparation failed.");
        publishRetry(callbackId, attemptNo, messageId, nextAttemptNo, safeReason);
    }

    private void publishExistingRetry(
            String callbackId, PaymentRefundCoordinationDecision decision) {
        if (decision.messageId() == null || decision.nextAttemptNo() <= 0
                || decision.safeReason() == null) {
            throw unavailable("Refund retry pending state is incomplete.");
        }
        publishRetry(
                callbackId,
                decision.attemptNo(),
                decision.messageId(),
                decision.nextAttemptNo(),
                decision.safeReason());
    }

    private void publishRetry(
            String callbackId,
            int attemptNo,
            String messageId,
            int nextAttemptNo,
            String safeReason) {
        long delayMillis = retryDelay(attemptNo);
        try {
            publisher.publishRetry(
                    messageId,
                    new MembershipRefundRetryMessage(
                            callbackId, nextAttemptNo, MAX_ATTEMPTS),
                    Duration.ofMillis(delayMillis));
        } catch (RuntimeException exception) {
            MembershipPaymentLifecycleDiagnostics.refundRetryScheduled(
                    nextAttemptNo,
                    delayMillis,
                    false,
                    MembershipPaymentTraceContext.currentTraceId());
            throw exception;
        }
        requireTransition(
                store.confirmRetry(callbackId, messageId, nextAttemptNo),
                "Refund retry confirmation coordination failed.");
        MembershipPaymentLifecycleDiagnostics.refundRetryScheduled(
                nextAttemptNo,
                delayMillis,
                true,
                MembershipPaymentTraceContext.currentTraceId());
    }

    private void publishNewTerminal(
            String callbackId,
            int attemptNo,
            PaymentProviderType provider,
            PaymentRefundTerminalOutcome outcome,
            String safeReason) {
        String messageId = publisher.newMessageId();
        requireTransition(
                store.prepareTerminal(
                        callbackId,
                        attemptNo,
                        messageId,
                        outcome,
                        safeReason),
                "Refund terminal preparation failed.");
        publishTerminal(
                callbackId, attemptNo, provider, messageId, outcome, safeReason);
    }

    private void publishExistingTerminal(
            String callbackId,
            PaymentProviderType provider,
            PaymentRefundCoordinationDecision decision) {
        if (decision.messageId() == null || decision.terminalOutcome() == null
                || decision.safeReason() == null) {
            throw unavailable("Refund terminal pending state is incomplete.");
        }
        publishTerminal(
                callbackId,
                decision.attemptNo(),
                provider,
                decision.messageId(),
                decision.terminalOutcome(),
                decision.safeReason());
    }

    private void publishTerminal(
            String callbackId,
            int attemptNo,
            PaymentProviderType provider,
            String messageId,
            PaymentRefundTerminalOutcome outcome,
            String safeReason) {
        publisher.publishTerminal(
                messageId,
                new MembershipRefundTerminalFailureMessage(
                        callbackId,
                        provider,
                        outcome,
                        safeReason,
                        attemptNo,
                        MAX_ATTEMPTS,
                        true));
        requireTransition(
                store.confirmTerminal(callbackId, messageId),
                "Refund terminal confirmation coordination failed.");
        MembershipPaymentLifecycleDiagnostics.refundTerminal(
                outcome.name().toLowerCase(java.util.Locale.ROOT),
                safeReason,
                true,
                MembershipPaymentTraceContext.currentTraceId());
    }

    private RefundFactCheck loadRefundFact(String callbackId) {
        Map<String, MembershipPaymentRefundTerminalFact> facts =
                persistenceService.findRefundTerminalFacts(List.of(callbackId));
        MembershipPaymentRefundTerminalFact fact = facts == null
                ? null
                : facts.get(callbackId);
        PaymentProviderType provider = provider(fact);
        if (fact == null) {
            return RefundFactCheck.failed(provider, "CALLBACK_FACT_MISSING");
        }
        if (!Arrays.equals(fact.getCallbackId(), idCodec.decode(callbackId))) {
            return RefundFactCheck.failed(provider, "CALLBACK_ID_MISMATCH");
        }
        if (!MembershipPaymentCallbackResolution.REFUND_REQUIRED.name()
                .equals(fact.getCallbackResolution())) {
            return RefundFactCheck.failed(provider, "CALLBACK_RESOLUTION_MISMATCH");
        }
        if (fact.getOrderStatus() != MembershipOrderStatus.CLOSED
                && fact.getOrderStatus() != MembershipOrderStatus.CANCELLED) {
            return RefundFactCheck.failed(provider, "ORDER_NOT_TERMINAL");
        }
        if (fact.getOrderEntitlementResolution()
                != MembershipOrderEntitlementResolution.REFUND_REQUIRED) {
            return RefundFactCheck.failed(provider, "ENTITLEMENT_NOT_REFUND_REQUIRED");
        }
        if (fact.getOrderProviderTradeNo() != null) {
            return RefundFactCheck.failed(provider, "ORDER_PROVIDER_TRADE_STILL_BOUND");
        }
        if (fact.getOrderId() == null || fact.getProviderTradeNo() == null
                || fact.getPaidAmountYuan() == null
                || fact.getPaidAmountYuan().signum() <= 0) {
            return RefundFactCheck.failed(provider, "CALLBACK_FACT_INCOMPLETE");
        }
        PaymentRefundCommand command = new PaymentRefundCommand(
                idCodec.encode(fact.getOrderId()),
                fact.getProviderTradeNo(),
                fact.getPaidAmountYuan());
        return RefundFactCheck.verified(provider, command);
    }

    private static PaymentProviderType provider(MembershipPaymentRefundTerminalFact fact) {
        if (fact != null) {
            try {
                return PaymentProviderReference.resolveTrade(fact.getProviderTradeNo());
            } catch (IllegalArgumentException ignored) {
                // 当前六号退款消息不携带 Provider；数据库引用损坏时仍需进入人工终态而不能再次请求外部接口。
            }
        }
        return PaymentProviderType.LIUHAO;
    }

    private long retryDelay(int attemptNo) {
        if (attemptNo < 1 || attemptNo >= MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Payment refund retry delay attempt is invalid.");
        }
        return retryDelaysMillis.get(attemptNo - 1);
    }

    private static void requireTransition(boolean transitioned, String message) {
        if (!transitioned) {
            throw unavailable(message);
        }
    }

    private static MembershipPaymentInfrastructureException unavailable(String message) {
        return new MembershipPaymentInfrastructureException(message);
    }

    /** 该检查结果把可信退款命令与固定失败原因绑定，失败时禁止调用 Provider。 */
    private record RefundFactCheck(
            boolean verified,
            PaymentProviderType provider,
            PaymentRefundCommand command,
            String safeReason) {

        private static RefundFactCheck verified(
                PaymentProviderType provider, PaymentRefundCommand command) {
            return new RefundFactCheck(true, provider, command, "VERIFIED");
        }

        private static RefundFactCheck failed(
                PaymentProviderType provider, String safeReason) {
            return new RefundFactCheck(false, provider, null, safeReason);
        }
    }
}
