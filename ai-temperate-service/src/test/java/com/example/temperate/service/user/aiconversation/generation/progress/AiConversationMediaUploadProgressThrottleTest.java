package com.example.temperate.service.user.aiconversation.generation.progress;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 验证媒体上传进度的节流规则，确保浏览器只接收有意义且不会遗漏状态变化的 SSE 更新。
 */
final class AiConversationMediaUploadProgressThrottleTest {

    @Test
    void emitsInitialStateAndEveryFivePercentOfKnownLengthUpload() {
        AtomicLong nowMillis = new AtomicLong(1_000L);
        AiConversationMediaUploadProgressThrottle throttle =
                new AiConversationMediaUploadProgressThrottle(nowMillis::get);

        assertThat(throttle.shouldPublish(progress(0L, 1_000L, 0, 1L))).isTrue();
        assertThat(throttle.shouldPublish(progress(40L, 1_000L, 4, 2L))).isFalse();
        assertThat(throttle.shouldPublish(progress(50L, 1_000L, 5, 3L))).isTrue();
    }

    @Test
    void emitsByteProgressAfterTwoHundredMillisecondsAndAlwaysEmitsStateChange() {
        AtomicLong nowMillis = new AtomicLong(1_000L);
        AiConversationMediaUploadProgressThrottle throttle =
                new AiConversationMediaUploadProgressThrottle(nowMillis::get);

        assertThat(throttle.shouldPublish(progress(0L, 1_000L, 0, 1L))).isTrue();
        nowMillis.addAndGet(199L);
        assertThat(throttle.shouldPublish(progress(10L, 1_000L, 1, 2L))).isFalse();
        nowMillis.incrementAndGet();
        assertThat(throttle.shouldPublish(progress(20L, 1_000L, 2, 3L))).isTrue();
        assertThat(throttle.shouldPublish(new AiConversationMediaUploadProgress(
                AiConversationMediaType.IMAGE,
                0,
                1,
                3,
                AiConversationMediaUploadState.VERIFYING,
                1_000L,
                1_000L,
                99,
                4L,
                null))).isTrue();
    }

    private static AiConversationMediaUploadProgress progress(
            long transferredBytes,
            long totalBytes,
            int percent,
            long sequence) {
        return new AiConversationMediaUploadProgress(
                AiConversationMediaType.IMAGE,
                0,
                1,
                3,
                AiConversationMediaUploadState.UPLOADING,
                transferredBytes,
                totalBytes,
                percent,
                sequence,
                null);
    }
}
