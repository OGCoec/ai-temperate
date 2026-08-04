package com.example.temperate.service.user.aiconversation.model.stream;

import com.example.temperate.service.user.aiconversation.model.AiConversationModelChunk;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImage;
import java.util.Objects;

/**
 * 表示模型上游被标准化后的流式事件联合类型，隔离 Chat Completions 与 Responses 的供应商协议差异。
 *
 * <p>Chunk 继续承载正文、Usage、终止原因和生成媒体；研究活动只在请求内转发，图片事件只进入本机预览通道，
 * 二者都不得把上游原始事件写入 Redis 或 PostgreSQL。</p>
 */
public sealed interface AiConversationModelEvent permits
        AiConversationModelEvent.Chunk,
        AiConversationModelEvent.Image,
        AiConversationModelEvent.Activity,
        AiConversationModelEvent.Source,
        AiConversationModelEvent.ReasoningSummaryDelta,
        AiConversationModelEvent.Failure {

    record Chunk(AiConversationModelChunk value)
            implements AiConversationModelEvent {

        public Chunk {
            value = Objects.requireNonNull(value);
        }
    }

    record Image(AiConversationGeneratedImage value)
            implements AiConversationModelEvent {

        public Image {
            value = Objects.requireNonNull(value);
        }
    }

    record Activity(
            String activityId,
            AiConversationActivityPhase phase,
            AiConversationActivityStatus status,
            String query) implements AiConversationModelEvent {

        public Activity {
            activityId = Objects.requireNonNull(activityId);
            phase = Objects.requireNonNull(phase);
            status = Objects.requireNonNull(status);
        }
    }

    record Source(
            String activityId,
            String sourceId,
            String title,
            String url,
            String domain,
            AiConversationSourceRole role) implements AiConversationModelEvent {

        public Source {
            activityId = Objects.requireNonNull(activityId);
            sourceId = Objects.requireNonNull(sourceId);
            title = Objects.requireNonNull(title);
            url = Objects.requireNonNull(url);
            domain = Objects.requireNonNull(domain);
            role = Objects.requireNonNull(role);
        }
    }

    record ReasoningSummaryDelta(
            String activityId,
            String textDelta) implements AiConversationModelEvent {

        public ReasoningSummaryDelta {
            activityId = Objects.requireNonNull(activityId);
            textDelta = Objects.requireNonNull(textDelta);
        }
    }

    record Failure(String reasonCode) implements AiConversationModelEvent {

        public Failure {
            reasonCode = Objects.requireNonNull(reasonCode);
        }
    }
}
