package com.example.temperate.service.user.aiconversation.model.impl;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

/**
 * 验证上游持续返回片段也不会重置十五分钟绝对时限，避免把总时限误实现成空闲超时。
 */
final class SpringAiCliProxyConversationModelClientTimeoutTest {

    @Test
    void activeChunksDoNotResetTheAbsoluteDeadline() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();

        StepVerifier.withVirtualTime(
                        () -> SpringAiCliProxyConversationModelClient
                                .enforceTotalDeadline(
                                        Flux.interval(
                                                        Duration.ofMinutes(2),
                                                        scheduler)
                                                .map(value -> Long.toString(value)),
                                        Duration.ofMinutes(15)),
                        () -> scheduler,
                        16)
                .thenAwait(Duration.ofMinutes(14))
                .expectNextCount(7)
                .thenAwait(Duration.ofMinutes(1))
                .expectError(TimeoutException.class)
                .verify();
    }
}
