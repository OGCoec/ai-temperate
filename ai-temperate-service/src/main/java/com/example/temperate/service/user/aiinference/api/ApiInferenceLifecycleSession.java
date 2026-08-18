package com.example.temperate.service.user.aiinference.api;

import com.example.temperate.service.user.aiinference.concurrency.AiInferenceConcurrencyPermit;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 该请求级会话是来原子裁决结算、退款与取消三种互斥终态，并确保同一并发租约最多释放一次。
 */
public final class ApiInferenceLifecycleSession {

    /** 终态状态只允许从 ACTIVE 单向迁移，恢复任务负责收敛 RECOVERY_PENDING。 */
    public enum TerminalState {
        ACTIVE,
        SETTLING,
        REFUNDING,
        CANCELLING,
        FINALIZED,
        RECOVERY_PENDING
    }

    private final AiInferenceConcurrencyPermit permit;
    private final ApiInferenceReservation reservation;
    private final ApiInferenceExecutionRequest request;
    private final AtomicReference<TerminalState> terminalState =
            new AtomicReference<>(TerminalState.ACTIVE);
    private final AtomicBoolean released = new AtomicBoolean();

    public ApiInferenceLifecycleSession(
            AiInferenceConcurrencyPermit permit,
            ApiInferenceReservation reservation,
            ApiInferenceExecutionRequest request) {
        this.permit = Objects.requireNonNull(permit);
        this.reservation = Objects.requireNonNull(reservation);
        this.request = Objects.requireNonNull(request);
    }

    public AiInferenceConcurrencyPermit permit() {
        return permit;
    }

    public ApiInferenceReservation reservation() {
        return reservation;
    }

    public ApiInferenceExecutionRequest request() {
        return request;
    }

    public TerminalState terminalState() {
        return terminalState.get();
    }

    public boolean beginTerminal(TerminalState target) {
        if (target != TerminalState.SETTLING
                && target != TerminalState.REFUNDING
                && target != TerminalState.CANCELLING) {
            throw new IllegalArgumentException("Unsupported active terminal transition");
        }
        return terminalState.compareAndSet(TerminalState.ACTIVE, target);
    }

    public void finalized() {
        terminalState.set(TerminalState.FINALIZED);
    }

    public void recoveryPending() {
        terminalState.set(TerminalState.RECOVERY_PENDING);
    }

    public boolean markReleased() {
        return released.compareAndSet(false, true);
    }
}
