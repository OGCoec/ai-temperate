package com.example.temperate.service.user.aiconversation.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 验证媒体上传指标只使用固定低基数标签，不会把对象 Key、URL 或用户标识带入监控系统。
 */
final class AiConversationMediaUploadMetricsTest {

    @Test
    void recordsFixedMediaTypeOutcomeAndDurationBucket() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiConversationMetrics metrics = new AiConversationMetrics(registry);

        metrics.mediaUpload(Duration.ofSeconds(6), "video", "success");

        Counter counter = registry.find("ai.conversation.media.upload")
                .tags("media_type", "video", "outcome", "success",
                        "duration_bucket", "5s_to_30s")
                .counter();
        assertEquals(1D, counter.count());
    }
}
