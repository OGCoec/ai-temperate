package com.example.temperate.service.user.aiconversation.diagnostic.impl;

import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamClientDiagnostic;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamClientDiagnosticRateLimitService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamClientDiagnosticService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingClock;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingContext;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingPath;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTransportDiagnosticService;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationService;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationView;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 将浏览器侧收到、解析和渲染的汇总时间安全关联到用户拥有的 Generation。
 * 归属与 usagePublicId 必须先校验，随后再做 Redis 去重，避免匿名或跨用户请求污染诊断链路。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class AiConversationStreamClientDiagnosticServiceImpl
        implements AiConversationStreamClientDiagnosticService {

    private static final String UNAVAILABLE = "unavailable";

    private final AiConversationGenerationService generationService;
    private final AiConversationStreamClientDiagnosticRateLimitService rateLimitService;
    private final AiConversationStreamTransportDiagnosticService transportDiagnosticService;
    private final AiConversationStreamTimingClock timingClock;

    public AiConversationStreamClientDiagnosticServiceImpl(
            AiConversationGenerationService generationService,
            AiConversationStreamClientDiagnosticRateLimitService rateLimitService,
            AiConversationStreamTransportDiagnosticService transportDiagnosticService,
            AiConversationStreamTimingClock timingClock) {
        this.generationService = Objects.requireNonNull(generationService);
        this.rateLimitService = Objects.requireNonNull(rateLimitService);
        this.transportDiagnosticService = Objects.requireNonNull(transportDiagnosticService);
        this.timingClock = Objects.requireNonNull(timingClock);
    }

    @Override
    public void record(
            long userId,
            byte[] generationId,
            AiConversationStreamClientDiagnostic diagnostic) {
        Objects.requireNonNull(generationId);
        Objects.requireNonNull(diagnostic);
        AiConversationGenerationView generation = generationService.getOwned(userId, generationId);
        // 不能仅信任浏览器传来的 Usage ID；它必须与该用户拥有的 Generation 完全一致。
        if (!generation.usagePublicId().equals(diagnostic.usagePublicId())) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_REQUEST_INVALID,
                    "AI stream diagnostic usage does not match generation.",
                    false);
        }
        if (!rateLimitService.tryAcquire(generation.generationPublicId())) {
            return;
        }
        AiConversationStreamTimingContext context = new AiConversationStreamTimingContext(
                validTraceId(diagnostic.traceId()),
                generation.usagePublicId(),
                generation.conversationPublicId(),
                UNAVAILABLE,
                AiConversationStreamTimingPath.BROWSER_CLIENT,
                timingClock.nanoTime());
        transportDiagnosticService.record(
                context,
                "ai_stream_browser_summary",
                Map.ofEntries(
                        Map.entry("outcome", diagnostic.outcome()),
                        Map.entry("responseHeadersMs", diagnostic.responseHeadersMs()),
                        Map.entry("firstByteMs", diagnostic.firstByteMs()),
                        Map.entry("lastNetworkByteMs", diagnostic.lastNetworkByteMs()),
                        Map.entry("firstHeartbeatMs", diagnostic.firstHeartbeatMs()),
                        Map.entry("firstDeltaMs", diagnostic.firstDeltaMs()),
                        Map.entry("completedMs", diagnostic.completedMs()),
                        Map.entry("networkReads", diagnostic.networkReads()),
                        Map.entry("networkBytes", diagnostic.networkBytes()),
                        Map.entry("parsedEvents", diagnostic.parsedEvents()),
                        Map.entry("renderedUpdates", diagnostic.renderedUpdates()),
                        Map.entry("renderedTextCharacters", diagnostic.renderedTextCharacters()),
                        Map.entry("lastDeltaSequence", diagnostic.lastDeltaSequence()),
                        Map.entry("deltaSequenceGapCount", diagnostic.deltaSequenceGapCount())));
    }

    private static String validTraceId(String value) {
        return value != null && value.matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
                ? value : UNAVAILABLE;
    }
}
