package com.example.temperate.service.user.aiconversation.response;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 验证单次流式请求只能由成功结算或中断结算中的一方取得终态所有权。
 */
final class AiConversationRequestLifecycleTest {

    @Test
    void finalUsageClaimPreventsLaterCancellationFromStartingInterruption() {
        AiConversationRequestLifecycle lifecycle =
                new AiConversationRequestLifecycle();
        lifecycle.markReserved();
        lifecycle.markConnecting();
        lifecycle.markStreaming();

        assertThat(lifecycle.tryBeginSuccessFinalization()).isTrue();
        assertThat(lifecycle.tryBeginInterruptedFinalization()).isFalse();

        lifecycle.markSettled();
        assertThat(lifecycle.state()).isEqualTo(
                AiConversationRequestState.SETTLED);
    }

    @Test
    void cancellationClaimPreventsLateUsageFromPersistingMessage() {
        AiConversationRequestLifecycle lifecycle =
                new AiConversationRequestLifecycle();
        lifecycle.markReserved();
        lifecycle.markConnecting();

        assertThat(lifecycle.tryBeginInterruptedFinalization()).isTrue();
        assertThat(lifecycle.tryBeginSuccessFinalization()).isFalse();

        lifecycle.markReconcileRequired();
        assertThat(lifecycle.state()).isEqualTo(
                AiConversationRequestState.RECONCILE_REQUIRED);
    }

    @Test
    void concurrentFinalizersAllowExactlyOneOwner() throws InterruptedException {
        AiConversationRequestLifecycle lifecycle =
                new AiConversationRequestLifecycle();
        lifecycle.markReserved();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        Thread success = Thread.ofVirtual().start(() -> {
            await(start);
            if (lifecycle.tryBeginSuccessFinalization()) {
                winners.incrementAndGet();
            }
        });
        Thread interrupted = Thread.ofVirtual().start(() -> {
            await(start);
            if (lifecycle.tryBeginInterruptedFinalization()) {
                winners.incrementAndGet();
            }
        });

        start.countDown();
        success.join();
        interrupted.join();

        assertThat(winners.get()).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
