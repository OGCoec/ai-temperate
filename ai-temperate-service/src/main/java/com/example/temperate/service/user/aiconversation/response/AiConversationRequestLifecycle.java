package com.example.temperate.service.user.aiconversation.response;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 使用 CAS 为单次流式请求选择唯一结算路径，防止成功、异常和客户端取消重复修改额度。
 */
public final class AiConversationRequestLifecycle {

    private final AtomicReference<AiConversationRequestState> state =
            new AtomicReference<>(AiConversationRequestState.PREPARED);

    public AiConversationRequestState state() {
        return state.get();
    }

    public void markReserved() {
        transitionRequired(
                AiConversationRequestState.PREPARED,
                AiConversationRequestState.RESERVED);
    }

    public void markConnecting() {
        transitionRequired(
                AiConversationRequestState.RESERVED,
                AiConversationRequestState.CONNECTING);
    }

    public void markStreaming() {
        state.compareAndSet(
                AiConversationRequestState.CONNECTING,
                AiConversationRequestState.STREAMING);
    }

    public boolean tryBeginSuccessFinalization() {
        return claimActive(AiConversationRequestState.FINALIZING_SUCCESS);
    }

    public boolean tryBeginInterruptedFinalization() {
        return claimActive(AiConversationRequestState.FINALIZING_INTERRUPTED);
    }

    public void markSettled() {
        transitionFinalizer(AiConversationRequestState.SETTLED);
    }

    public void markFailedRefunded() {
        transitionFinalizer(AiConversationRequestState.FAILED_REFUNDED);
    }

    public void markReconcileRequired() {
        transitionFinalizer(AiConversationRequestState.RECONCILE_REQUIRED);
    }

    private boolean claimActive(AiConversationRequestState target) {
        while (true) {
            AiConversationRequestState current = state.get();
            if (current != AiConversationRequestState.RESERVED
                    && current != AiConversationRequestState.CONNECTING
                    && current != AiConversationRequestState.STREAMING) {
                return false;
            }
            if (state.compareAndSet(current, target)) {
                return true;
            }
        }
    }

    private void transitionFinalizer(AiConversationRequestState target) {
        AiConversationRequestState current = state.get();
        if (current != AiConversationRequestState.FINALIZING_SUCCESS
                && current != AiConversationRequestState.FINALIZING_INTERRUPTED) {
            throw new IllegalStateException(
                    "AI conversation request has no finalization ownership.");
        }
        transitionRequired(current, target);
    }

    private void transitionRequired(
            AiConversationRequestState expected,
            AiConversationRequestState target) {
        if (!state.compareAndSet(expected, target)) {
            throw new IllegalStateException(
                    "Invalid AI conversation request state transition: "
                            + state.get() + " -> " + target);
        }
    }
}
