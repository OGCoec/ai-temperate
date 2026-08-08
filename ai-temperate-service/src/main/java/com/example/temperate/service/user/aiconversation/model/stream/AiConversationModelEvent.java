package com.example.temperate.service.user.aiconversation.model.stream;

import com.example.temperate.service.user.aiconversation.model.AiConversationModelChunk;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImage;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageMeteringEvidence;
import com.example.temperate.service.user.aiconversation.model.AiConversationMeteredUsage;
import com.example.temperate.service.user.aiconversation.video.AiConversationGeneratedVideo;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMeteringEvidence;
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
        AiConversationModelEvent.ImageOutputReady,
        AiConversationModelEvent.ImageUsage,
        AiConversationModelEvent.ImageCostEvidence,
		AiConversationModelEvent.ImageFailure,
		AiConversationModelEvent.VideoRequestAccepted,
		AiConversationModelEvent.VideoProgress,
        AiConversationModelEvent.Video,
        AiConversationModelEvent.VideoCostEvidence,
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

    /**
     * 表示一个图片子流已经同时交付最终字节和计量闭环，可以独立进入 OSS 持久化而无需等待兄弟槽位。
     */
    record ImageOutputReady(short outputIndex)
            implements AiConversationModelEvent {

        public ImageOutputReady {
            if (outputIndex < 0 || outputIndex > 9) {
                throw new IllegalArgumentException("Image output index is out of range.");
            }
        }
    }

    /**
     * 将每个图片子流的权威用量和代表请求 ID 绑定到稳定输出序号，防止乱序完成时重复计费。
     */
    record ImageUsage(
            short outputIndex,
            AiConversationMeteredUsage usage,
            String upstreamRequestId,
            String finishReason) implements AiConversationModelEvent {

        public ImageUsage {
            if (outputIndex < 0 || outputIndex > 9) {
                throw new IllegalArgumentException("Image output index is out of range.");
            }
            usage = Objects.requireNonNull(usage);
        }
    }

    /**
     * 表示图片已经生成但供应商成本证据缺失或非法；该事件保留图片并把整个任务转入待对账。
     */
    record ImageCostEvidence(AiConversationImageMeteringEvidence evidence)
            implements AiConversationModelEvent {

        public ImageCostEvidence {
            evidence = Objects.requireNonNull(evidence);
        }
    }

    /**
     * 表示单路图片上游失败；外层任务保留该槽位失败事实，同时允许其他子流继续完成。
     */
    record ImageFailure(short outputIndex, Throwable cause)
            implements AiConversationModelEvent {

        public ImageFailure {
            if (outputIndex < 0 || outputIndex > 9) {
                throw new IllegalArgumentException("Image output index is out of range.");
            }
            cause = Objects.requireNonNull(cause);
        }
    }

    /**
     * 在创建 POST 返回后立即冻结 xAI 请求标识，使后续轮询超时或断网仍可人工对账。
     */
    record VideoRequestAccepted(String requestId)
            implements AiConversationModelEvent {

        public VideoRequestAccepted {
            requestId = Objects.requireNonNull(requestId);
        }
    }

    /**
     * 表示 xAI 异步任务的协议进度，不携带视频地址或供应商原始正文。
     */
    record VideoProgress(int progress) implements AiConversationModelEvent {

        public VideoProgress {
            if (progress < 0 || progress > 100) {
                throw new IllegalArgumentException("Video progress is invalid.");
            }
        }
    }

    /**
     * 表示已生成但尚未进入 OSS 的临时视频，外层 Worker 必须立即交给 FC 且不得持久化其中的 URL。
     */
    record Video(AiConversationGeneratedVideo value)
            implements AiConversationModelEvent {

        public Video {
            value = Objects.requireNonNull(value);
        }
    }

    /**
     * 表示 xAI 视频任务的精确成本或缺失成本证据，供终态结算与人工对账使用。
     */
    record VideoCostEvidence(AiConversationVideoMeteringEvidence evidence)
            implements AiConversationModelEvent {

        public VideoCostEvidence {
            evidence = Objects.requireNonNull(evidence);
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
