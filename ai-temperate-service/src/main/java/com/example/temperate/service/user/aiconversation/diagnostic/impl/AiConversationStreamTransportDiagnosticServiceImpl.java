package com.example.temperate.service.user.aiconversation.diagnostic.impl;

import com.example.temperate.service.user.aiconversation.config.AiConversationStreamDiagnosticsProperties;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingClock;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingContext;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTransportDiagnosticService;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 按流式诊断采样开关输出跨传输边界的安全时间摘要，供同一 Generation 的后端与边缘日志关联。
 * 该实现不落库、不写入 Redis，也不会把正文放入日志，避免诊断本身成为新的数据存储路径。
 */
@Service
public final class AiConversationStreamTransportDiagnosticServiceImpl
        implements AiConversationStreamTransportDiagnosticService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            AiConversationStreamTransportDiagnosticServiceImpl.class);
    private static final Set<String> ALLOWED_DETAIL_KEYS = Set.of(
            "generationPublicId",
            "usagePublicId",
            "revision",
            "flushReason",
            "chunkCount",
            "deltaBytes",
            "deltaChars",
            "redisAppendElapsedMs",
            "redisPublishElapsedMs",
            "redisAppendStartElapsedMs",
            "redisAppendEndElapsedMs",
            "redisPublishStartElapsedMs",
            "redisPublishEndElapsedMs",
            "eventType",
            "bytes",
            "requestUri",
            "statusCode",
            "firstWrite",
            "failureStage",
            "failureType",
            "outcome",
            "responseHeadersMs",
            "firstByteMs",
            "lastNetworkByteMs",
            "firstHeartbeatMs",
            "firstDeltaMs",
            "completedMs",
            "networkReads",
            "networkBytes",
            "parsedEvents",
            "renderedUpdates",
            "renderedTextCharacters",
            "lastDeltaSequence",
            "deltaSequenceGapCount");

    private final AiConversationStreamDiagnosticsProperties properties;
    private final AiConversationStreamTimingClock clock;

    public AiConversationStreamTransportDiagnosticServiceImpl(
            AiConversationStreamDiagnosticsProperties properties,
            AiConversationStreamTimingClock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void record(
            AiConversationStreamTimingContext context,
            String event,
            Map<String, ?> details) {
        Objects.requireNonNull(context);
        if (!properties.shouldSample(context.usagePublicId())) {
            return;
        }
        Map<String, Object> safeDetails = safeDetails(details);
        LOGGER.info(
                "event={} occurredAt={} elapsedMs={} traceId={} usagePublicId={} "
                        + "conversationPublicId={} modelPublicId={} path={} details={}",
                safe(event),
                Instant.now(),
                elapsedMillis(context),
                safe(context.traceId()),
                safe(context.usagePublicId()),
                safe(context.conversationPublicId()),
                safe(context.modelPublicId()),
                context.path(),
                safeDetails);
    }

    private Map<String, Object> safeDetails(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        details.forEach((key, value) -> {
            if (ALLOWED_DETAIL_KEYS.contains(key)) {
                safe.put(key, safeValue(value));
            }
        });
        return Collections.unmodifiableMap(safe);
    }

    private static Object safeValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return safe(String.valueOf(value));
    }

    private long elapsedMillis(AiConversationStreamTimingContext context) {
        long elapsedNanos = clock.nanoTime() - context.startedNanos();
        return Math.max(0L, elapsedNanos / 1_000_000L);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "unavailable";
        }
        return value.length() <= 128 ? value : value.substring(0, 128);
    }
}
