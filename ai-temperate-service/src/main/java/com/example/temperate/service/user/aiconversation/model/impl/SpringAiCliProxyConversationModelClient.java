package com.example.temperate.service.user.aiconversation.model.impl;

import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentService;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentCategory;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentState;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationGeneratedMedia;
import com.example.temperate.service.user.aiconversation.attachment.config.AiConversationAttachmentProperties;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.context.AiConversationTurn;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureClassification;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureClassifier;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTiming;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleTimed;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingBoundary;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingDiagnosticService;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.exception.AiConversationStreamFailureReason;
import io.micrometer.core.instrument.Metrics;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelChunk;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelClient;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelRequest;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelMediaLoader;
import com.example.temperate.service.user.aiconversation.model.AiConversationUsage;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.concurrent.Queues;

/**
 * 使用 Spring AI 普通 OpenAI Starter 将项目 Prompt 适配为 CLIProxyAPI 的单次流式 Chat Completions 调用。
 *
 * <p>该客户端精确依赖不会全量收集响应的 {@link OpenAiChatModel}，不自动重试、不读取 Redis、
 * 不执行计费；cached/reasoning Token 从普通 OpenAI 原生 usage 提取，缺少最终 usage 时由上层进入待对账路径。</p>
 */
@Service
public final class SpringAiCliProxyConversationModelClient
        implements AiConversationModelClient {

    private final ObjectProvider<OpenAiChatModel> chatModelProvider;
    private final AiInferenceProperties properties;
    private final AiConversationAttachmentService attachmentService;
    private final AiConversationAttachmentProperties attachmentProperties;
    private final AiConversationModelMediaLoader mediaLoader;
    private final AiConversationStreamFailureClassifier failureClassifier;
    private final AiConversationStreamTimingDiagnosticService
            timingDiagnosticService;

    public SpringAiCliProxyConversationModelClient(
            ObjectProvider<OpenAiChatModel> chatModelProvider,
            AiInferenceProperties properties,
            AiConversationAttachmentService attachmentService,
            AiConversationAttachmentProperties attachmentProperties,
            AiConversationModelMediaLoader mediaLoader,
            AiConversationStreamFailureClassifier failureClassifier,
            AiConversationStreamTimingDiagnosticService
                    timingDiagnosticService) {
        this.chatModelProvider = Objects.requireNonNull(chatModelProvider);
        this.properties = Objects.requireNonNull(properties);
        this.attachmentService = Objects.requireNonNull(attachmentService);
        this.attachmentProperties = Objects.requireNonNull(attachmentProperties);
        this.mediaLoader = Objects.requireNonNull(mediaLoader);
        this.failureClassifier = Objects.requireNonNull(failureClassifier);
        this.timingDiagnosticService = Objects.requireNonNull(
                timingDiagnosticService);
    }

    @Override
    @AiConversationStreamTiming
    @AiConversationLifecycleTimed(stage = "UPSTREAM_MODEL")
    public Flux<AiConversationModelChunk> stream(
            AiConversationModelRequest request) {
        if (!properties.enabled()) {
            return Flux.error(new AiConversationException(
                    AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE,
                    "模型推理功能当前未启用",
                    true));
        }
        return Flux.defer(() -> {
            ChatClient chatClient = ChatClient.create(requiredChatModel());
            Prompt prompt = new Prompt(
                    messages(request.prompt()),
                    streamOptions(request));
            GeneratedMediaLoadBudget mediaBudget = new GeneratedMediaLoadBudget(
                    attachmentProperties.maxFilesPerMessage(),
                    attachmentProperties.maxTotalBytesPerMessage());
            Flux<ChatResponse> raw = chatClient.prompt(prompt)
                    .stream()
                    .chatResponse();
            Flux<ChatResponse> observedRaw = timingDiagnosticService
                    .observeBoundary(
                            raw,
                            AiConversationStreamTimingBoundary.SPRING_AI_RAW,
                            SpringAiCliProxyConversationModelClient
                                    ::rawTextCharacters);
            // 显式写出 Reactor 默认预取值，使诊断等待队列可以按同一容量加固定余量保持有界。
            Flux<ChatResponse> scheduled = observedRaw.publishOn(
                    Schedulers.boundedElastic(), Queues.SMALL_BUFFER_SIZE);
            Flux<ChatResponse> observedScheduled = timingDiagnosticService
                    .observeBoundary(
                            scheduled,
                            AiConversationStreamTimingBoundary
                                    .AFTER_BOUNDED_ELASTIC,
                            SpringAiCliProxyConversationModelClient
                                    ::rawTextCharacters);
            Flux<AiConversationModelChunk> upstream = observedScheduled
                    .map(response -> mapResponse(response, mediaBudget));
            // Extra High 等深度推理可能长时间没有文本片；这里只保留从订阅开始计算的总时限，
            // 心跳和中间片段都不会重新计时，超时必须显式进入系统失败退款路径。
            return enforceTotalDeadline(
                            upstream, properties.maxStreamDuration())
                    .onErrorMap(this::mapFailure);
        });
    }

    private static int rawTextCharacters(ChatResponse response) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            return 0;
        }
        return response.getResult().getOutput().getText().length();
    }

    static <T> Flux<T> enforceTotalDeadline(
            Flux<T> upstream, Duration maximumDuration) {
        return upstream.takeUntilOther(totalDeadline(maximumDuration));
    }

    private static Mono<Void> totalDeadline(Duration maximumDuration) {
        return Mono.delay(maximumDuration)
                .then(Mono.error(new TimeoutException(
                        "AI upstream maximum stream duration exceeded.")));
    }

    @Override
    public String compact(
            com.example.temperate.service.user.aiconversation.model.AiModelProvider provider,
            String modelName,
            String compactionPrompt) {
        Objects.requireNonNull(provider);
        if (!properties.enabled()) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE,
                    "模型推理功能当前未启用",
                    true);
        }
        return ChatClient.create(requiredChatModel())
                .prompt()
                .system("你是内部会话压缩器，只保留事实、决定和未完成事项，不添加新事实。")
                .user(compactionPrompt)
                .options(compactionOptions(modelName, 4096L))
                .call()
                .content();
    }

    private OpenAiChatModel requiredChatModel() {
        OpenAiChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE,
                    "模型推理客户端未配置",
                    true);
        }
        return model;
    }

    private static OpenAiChatOptions streamOptions(
            AiConversationModelRequest request) {
        int maxCompletionTokens =
                maxCompletionTokens(request.maxOutputTokens());
        return OpenAiChatOptions.builder()
                .model(request.modelName())
                .maxCompletionTokens(maxCompletionTokens)
                .reasoningEffort(request.reasoningEffort().upstreamValue())
                .N(1)
                .store(false)
                .streamUsage(true)
                .build();
    }

    private static OpenAiChatOptions compactionOptions(
            String modelName, long maxOutputTokens) {
        int maxCompletionTokens = maxCompletionTokens(maxOutputTokens);
        return OpenAiChatOptions.builder()
                .model(modelName)
                .maxCompletionTokens(maxCompletionTokens)
                .N(1)
                .store(false)
                .streamUsage(true)
                .build();
    }

    private static int maxCompletionTokens(long maxOutputTokens) {
        final int maxCompletionTokens;
        try {
            maxCompletionTokens = Math.toIntExact(maxOutputTokens);
        } catch (ArithmeticException exception) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_MODEL_LIMITS_MISSING,
                    "模型最大输出限制超出上游协议范围",
                    false);
        }
        return maxCompletionTokens;
    }

    private List<Message> messages(
            AiConversationPromptSnapshot snapshot) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(snapshot.systemPrompt()));
        if (snapshot.durableCompactionJson() != null) {
            messages.add(new SystemMessage(
                    "以下 JSON 是已持久化历史的压缩摘要，仅作为既有上下文：\n"
                            + snapshot.durableCompactionJson()));
        }
        if (snapshot.ephemeralCompactionJson() != null) {
            messages.add(new SystemMessage(
                    "以下 JSON 是可过期中断回答的临时摘要，仅作为既有上下文：\n"
                            + snapshot.ephemeralCompactionJson()));
        }
        for (AiConversationTurn turn : snapshot.historicalTurns()) {
            messages.add(userMessage(turn.user(), true));
            messages.add(assistantMessage(turn.assistant()));
        }
        messages.add(userMessage(snapshot.currentInput(), false));
        return List.copyOf(messages);
    }

    private UserMessage userMessage(
            AiConversationContent content,
            boolean ignoreExpiredTemporaryAttachments) {
        List<Media> media = new ArrayList<>();
        content.attachments().forEach(attachment -> {
            if (attachment.state() != AiConversationAttachmentState.AVAILABLE) {
                return;
            }
            if (attachment.category() == AiConversationAttachmentCategory.AUDIO
                    || attachment.category()
                    == AiConversationAttachmentCategory.VIDEO) {
                if (ignoreExpiredTemporaryAttachments) {
                    // 普通 OpenAI 1.1.8 不能把历史音视频 URL 可靠编码进 Chat Completions；
                    // 历史轮次保留文字，避免把音视频错误伪装成 image_url。
                    return;
                }
                throw new AiConversationException(
                        AiConversationErrorCode
                                .AI_ATTACHMENT_CAPABILITY_UNSUPPORTED,
                        "当前模型流式客户端暂不支持音频或视频附件",
                        false);
            }
            if (attachment.category() != AiConversationAttachmentCategory.IMAGE) {
                return;
            }
            try {
                media.add(modelInputMedia(attachment));
            } catch (AiConversationException exception) {
                if (!ignoreExpiredTemporaryAttachments
                        || !attachment.url().startsWith("ait-temp:")) {
                    throw exception;
                }
                // 临时 OSS 生命周期短于 Redis；历史中断附件过期后只忽略附件，文字上下文仍然保留。
                Metrics.counter(
                                "ai.conversation.context.attachment",
                                "outcome",
                                "expired")
                        .increment();
            }
        });
        return UserMessage.builder()
                .text(content.text())
                .media(List.copyOf(media))
                .build();
    }

    private AssistantMessage assistantMessage(AiConversationContent content) {
        // 普通 OpenAI 1.1.8 会把 Assistant 媒体解释为音频输出引用，而不是历史输入附件；
        // 因此历史 Assistant 只回放已持久化文字，生成媒体仍由本地历史记录负责展示。
        return new AssistantMessage(content.text());
    }

    /**
     * 把已经通过附件服务授权的模型 URL 转成普通 OpenAI 模型支持的图片媒体数据。
     *
     * <p>Spring AI 1.1.8 的公开构造器接收 URI，并在 {@link Media} 内部保存为 URL 字符串；
     * 这层只执行 URI 语法校验，不负责重新签名或下载附件。</p>
     */
    private Media modelInputMedia(AiConversationAttachment attachment) {
        String modelUrl = attachmentService.resolveModelUrl(attachment);
        return new Media(
                MimeType.valueOf(attachment.contentType()),
                URI.create(modelUrl));
    }

    private AiConversationModelChunk mapResponse(
            ChatResponse response,
            GeneratedMediaLoadBudget mediaBudget) {
        String text = response.getResult() == null
                || response.getResult().getOutput() == null
                ? ""
                : response.getResult().getOutput().getText();
        String requestId = response.getMetadata() == null
                ? null
                : response.getMetadata().getId();
        String finishReason = response.getResult() == null
                || response.getResult().getMetadata() == null
                || response.getResult().getMetadata().getFinishReason() == null
                ? null
                : response.getResult().getMetadata().getFinishReason();
        AiConversationUsage usage = extractUsage(response).orElse(null);
        GeneratedMediaBatch generatedMedia = response.getResult() == null
                || response.getResult().getOutput() == null
                ? GeneratedMediaBatch.empty()
                : loadGeneratedMedia(response.getResult().getOutput(), mediaBudget);
        return new AiConversationModelChunk(
                text,
                usage,
                requestId,
                finishReason,
                generatedMedia.media(),
                generatedMedia.truncated());
    }

    private GeneratedMediaBatch loadGeneratedMedia(
            AssistantMessage output,
            GeneratedMediaLoadBudget budget) {
        List<Media> media = output.getMedia();
        if (media == null || media.isEmpty()) {
            return GeneratedMediaBatch.empty();
        }
        List<AiConversationGeneratedMedia> loaded = new ArrayList<>(
                Math.min(media.size(), budget.remainingFiles()));
        boolean truncated = false;
        for (int index = 0; index < media.size(); index++) {
            if (!budget.canLoad()) {
                truncated = true;
                budget.markTruncated();
                break;
            }
            long maximumBytes = Math.min(
                    attachmentProperties.maxFileBytes(),
                    budget.remainingBytes());
            AiConversationGeneratedMedia generated =
                    mediaLoader.load(media.get(index), index + 1, maximumBytes);
            long mediaBytes = generated.bytes().length;
            if (mediaBytes > budget.remainingBytes()) {
                truncated = true;
                budget.markTruncated();
                break;
            }
            loaded.add(generated);
            budget.accept(mediaBytes);
        }
        return new GeneratedMediaBatch(
                List.copyOf(loaded),
                truncated || budget.truncated());
    }

    /**
     * 同时携带已接受媒体与截断信号，避免通过伪造附件来通知上层存在未落盘内容。
     */
    private record GeneratedMediaBatch(
            List<AiConversationGeneratedMedia> media,
            boolean truncated) {

        private static GeneratedMediaBatch empty() {
            return new GeneratedMediaBatch(List.of(), false);
        }
    }

    /**
     * 为单次上游流维护生成媒体的累计加载预算，避免跨数据片重复分配无界内存。
     */
    private static final class GeneratedMediaLoadBudget {

        private int remainingFiles;
        private long remainingBytes;
        private boolean truncated;

        private GeneratedMediaLoadBudget(int remainingFiles, long remainingBytes) {
            this.remainingFiles = remainingFiles;
            this.remainingBytes = remainingBytes;
        }

        private boolean canLoad() {
            return remainingFiles > 0 && remainingBytes > 0L;
        }

        private int remainingFiles() {
            return remainingFiles;
        }

        private long remainingBytes() {
            return remainingBytes;
        }

        private void accept(long bytes) {
            remainingFiles--;
            remainingBytes -= bytes;
        }

        private void markTruncated() {
            truncated = true;
        }

        private boolean truncated() {
            return truncated;
        }
    }

    private static Optional<AiConversationUsage> extractUsage(
            ChatResponse response) {
        if (response.getMetadata() == null
                || response.getMetadata().getUsage() == null) {
            return Optional.empty();
        }
        Usage usage = response.getMetadata().getUsage();
        long prompt = number(usage.getPromptTokens());
        long completion = number(usage.getCompletionTokens());
        if (prompt == 0L && completion == 0L) {
            return Optional.empty();
        }
        long cached = 0L;
        long reasoning = 0L;
        Object nativeUsage = usage.getNativeUsage();
        if (nativeUsage instanceof OpenAiApi.Usage openAiUsage) {
            // 普通 OpenAI Starter 的原生 Usage 使用单数 completionTokenDetails；
            // 这里保持强类型读取，避免 SDK 旧字段名被静默反射为零而破坏结算明细。
            OpenAiApi.Usage.PromptTokensDetails promptDetails =
                    openAiUsage.promptTokensDetails();
            OpenAiApi.Usage.CompletionTokenDetails completionDetails =
                    openAiUsage.completionTokenDetails();
            cached = promptDetails == null
                    ? 0L
                    : number(promptDetails.cachedTokens());
            reasoning = completionDetails == null
                    ? 0L
                    : number(completionDetails.reasoningTokens());
        }
        return Optional.of(new AiConversationUsage(
                prompt,
                Math.min(prompt, cached),
                completion,
                reasoning));
    }

    private Throwable mapFailure(Throwable failure) {
        if (failure instanceof AiConversationException) {
            return failure;
        }
        AiConversationStreamFailureClassification classification =
                failureClassifier.classify(failure);
        AiConversationStreamFailureReason reason = classification.reason();
        AiConversationErrorCode code = switch (reason) {
            case UPSTREAM_TOTAL_TIMEOUT ->
                    AiConversationErrorCode.AI_UPSTREAM_TIMEOUT;
            case UPSTREAM_RATE_LIMITED,
                    UPSTREAM_AUTH_UNAVAILABLE,
                    UPSTREAM_SERVER_ERROR ->
                    AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE;
            default -> AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED;
        };
        String message = switch (code) {
            case AI_UPSTREAM_TIMEOUT -> "模型响应超时";
            case AI_UPSTREAM_UNAVAILABLE -> "模型上游暂时不可用";
            default -> "模型响应未能完成";
        };
        return new AiConversationException(
                code,
                message,
                true,
                reason,
                failure);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

}
