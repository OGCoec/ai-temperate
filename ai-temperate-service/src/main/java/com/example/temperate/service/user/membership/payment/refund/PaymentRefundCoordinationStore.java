package com.example.temperate.service.user.membership.payment.refund;

/**
 * 该存储契约是来以 callbackId 原子协调退款尝试与 Rabbit Confirm，Redis 只承担二十四小时并发和重投幂等。
 */
public interface PaymentRefundCoordinationStore {

    PaymentRefundCoordinationDecision beginInitial(String callbackId);

    PaymentRefundCoordinationDecision claimRetry(
            String callbackId, int attemptNo, String messageId);

    boolean markSucceeded(String callbackId, int attemptNo);

    boolean prepareRetry(
            String callbackId,
            int attemptNo,
            String messageId,
            int nextAttemptNo,
            String safeReason);

    boolean confirmRetry(String callbackId, String messageId, int nextAttemptNo);

    boolean prepareTerminal(
            String callbackId,
            int attemptNo,
            String messageId,
            PaymentRefundTerminalOutcome outcome,
            String safeReason);

    boolean confirmTerminal(String callbackId, String messageId);
}
