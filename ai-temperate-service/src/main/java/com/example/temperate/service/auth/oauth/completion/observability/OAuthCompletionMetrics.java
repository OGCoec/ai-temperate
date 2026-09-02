package com.example.temperate.service.auth.oauth.completion.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 记录 OAuth 完成权的并发冲突次数，不接收 Flow、Token、设备、IP 或其他高基数标签。
 */
@Component
public final class OAuthCompletionMetrics {

    private final Counter completionInProgress;
    private final Counter alreadyCompleted;

    public OAuthCompletionMetrics(MeterRegistry meterRegistry) {
        MeterRegistry registry = Objects.requireNonNull(meterRegistry);
        completionInProgress = registry.counter("oauth_completion_in_progress");
        alreadyCompleted = registry.counter("oauth_completion_already_completed");
    }

    public void completionInProgress() {
        completionInProgress.increment();
    }

    public void alreadyCompleted() {
        alreadyCompleted.increment();
    }
}
