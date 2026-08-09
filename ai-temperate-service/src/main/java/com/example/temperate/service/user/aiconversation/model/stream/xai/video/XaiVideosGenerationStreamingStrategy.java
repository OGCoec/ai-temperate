package com.example.temperate.service.user.aiconversation.model.stream.xai.video;

import com.example.temperate.service.user.aiconversation.config.AiConversationVideoGenerationProperties;
import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingProtocol;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingStrategy;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMeteringEvidence;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 创建一次 xAI 视频任务并按官方协议轮询到终态，输出只包含进度、临时定位和精确成本事件。
 *
 * <p>POST 创建操作严格执行一次；重复 GET 是异步状态协议的一部分，任何单次网络失败都会终止而不会自动重试。</p>
 */
@Service
public final class XaiVideosGenerationStreamingStrategy
        implements AiConversationStreamingStrategy {

    private final AiInferenceProperties inferenceProperties;
    private final AiConversationVideoGenerationProperties videoProperties;
    private final XaiVideoOperationStrategyRegistry operationRegistry;
    private final XaiVideoClient client;

    public XaiVideosGenerationStreamingStrategy(
            AiInferenceProperties inferenceProperties,
            AiConversationVideoGenerationProperties videoProperties,
            XaiVideoOperationStrategyRegistry operationRegistry,
            XaiVideoClient client) {
        this.inferenceProperties = Objects.requireNonNull(inferenceProperties);
        this.videoProperties = Objects.requireNonNull(videoProperties);
        this.operationRegistry = Objects.requireNonNull(operationRegistry);
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public AiModelProvider provider() {
        return AiModelProvider.XAI;
    }

    @Override
    public AiConversationStreamingProtocol protocol() {
        return AiConversationStreamingProtocol.VIDEOS_GENERATION;
    }

    @Override
    public AiConversationMeteringBasis meteringBasis() {
        return AiConversationMeteringBasis.PROVIDER_COST_TICKS;
    }

    @Override
    public Flux<AiConversationModelEvent> stream(
            AiConversationStreamingRequest request) {
        if (request.modelRequest().provider() != provider()
                || request.webSearchMode() != AiConversationWebSearchMode.OFF
                || request.modelRequest().videoGeneration() == null
                || request.modelRequest().imageGeneration() != null) {
            return Flux.error(invalid("xAI 视频请求参数与协议不匹配。"));
        }
        if (!inferenceProperties.enabled() || !videoProperties.enabled()) {
            return Flux.error(new AiConversationException(
                    AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE,
                    "xAI 视频生成功能当前未启用。",
                    true));
        }
        XaiVideoOperationContext context = new XaiVideoOperationContext(
                request.modelRequest().modelName(),
                request.modelRequest().prompt().currentInput().text(),
                request.modelRequest().videoGeneration(),
                request.modelRequest().videoInputUrls());
        XaiVideoStartRequest startRequest = operationRegistry
                .getRequired(context.options().mode())
                .buildRequest(context);
        return Flux.defer(() -> {
            Duration maximumWait = videoProperties.maximumPollingDuration();
            long deadlineNanos = System.nanoTime() + maximumWait.toNanos();
            Flux<AiConversationModelEvent> source;
            if (request.resumeUpstreamRequestId() == null) {
                source = client.start(startRequest)
                        .flatMapMany(start -> acceptedThenPoll(start.requestId()));
            } else {
                // Worker 恢复时只能使用已经冻结的 request_id 继续 GET，绝不能再次创建供应商任务。
                String requestId = new XaiVideoStartResult(
                        request.resumeUpstreamRequestId()).requestId();
                source = acceptedThenPoll(requestId);
            }
            Flux<AiConversationModelEvent> timeout = Flux.error(
                    new AiConversationException(
                            AiConversationErrorCode.AI_VIDEO_XAI_RESULT_UNCERTAIN,
                            "xAI 视频生成等待超时，任务已转入人工对账。",
                            true));
            // 每次事件后的剩余等待时间都指向同一截止点，确保持续进度也不能突破十五分钟总上限。
            return source.timeout(
                    reactor.core.publisher.Mono.delay(maximumWait),
                    ignored -> reactor.core.publisher.Mono.delay(
                            remaining(deadlineNanos)),
                    timeout);
        });
    }

    private Flux<AiConversationModelEvent> acceptedThenPoll(String requestId) {
        return Flux.concat(
                Flux.just(new AiConversationModelEvent.VideoRequestAccepted(requestId)),
                poll(requestId));
    }

    private Flux<AiConversationModelEvent> poll(String requestId) {
        // 固定 interval 会在单次 GET 超过轮询间隔时因下游暂无需求而溢出；
        // 每次响应完成后再启动下一段延迟，既保证单请求串行，也不会积压过期 tick。
        return Mono.delay(videoProperties.pollInterval())
                .then(Mono.defer(() -> client.poll(requestId)))
                .repeat()
                .takeUntil(result -> result.status().terminal())
                .concatMap(result -> events(requestId, result));
    }

    private static Flux<AiConversationModelEvent> events(
            String requestId,
            XaiVideoPollResult result) {
        List<AiConversationModelEvent> events = new ArrayList<>();
        events.add(new AiConversationModelEvent.VideoProgress(result.progress()));
        if (result.status() == XaiVideoStatus.PENDING) {
            return Flux.fromIterable(events);
        }
        events.add(new AiConversationModelEvent.VideoCostEvidence(
                new AiConversationVideoMeteringEvidence(
                        requestId, result.costInUsdTicks())));
        if (result.status() == XaiVideoStatus.DONE) {
            events.add(new AiConversationModelEvent.Video(result.video()));
            return Flux.fromIterable(events);
        }
        return Flux.concat(
                Flux.fromIterable(events),
                Flux.error(new AiConversationException(
                        result.status() == XaiVideoStatus.EXPIRED
                                ? AiConversationErrorCode.AI_VIDEO_XAI_EXPIRED
                                : AiConversationErrorCode.AI_VIDEO_XAI_FAILED,
                        result.status() == XaiVideoStatus.EXPIRED
                                ? "xAI 视频任务已过期。"
                                : "xAI 视频生成失败。",
                        false)));
    }

    private static Duration remaining(long deadlineNanos) {
        long nanos = deadlineNanos - System.nanoTime();
        return Duration.ofNanos(Math.max(1L, nanos));
    }

    private static AiConversationException invalid(String message) {
        return new AiConversationException(
                AiConversationErrorCode.AI_REQUEST_INVALID,
                message,
                false);
    }
}
