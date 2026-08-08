package com.example.temperate.service.user.aiconversation.diagnostic.impl;

import com.example.temperate.service.user.aiconversation.config.AiConversationStreamDiagnosticsProperties;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingClock;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingContext;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTransportDiagnosticService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
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
            "deltaSequenceGapCount",
            "requestedCount",
            "successfulCount",
            "subrequests",
            "checkpoint",
            "outputIndex",
            "imageAction",
            "requestPath",
            "responseContentType",
            "upstreamEventName",
            "upstreamJsonType",
            "partialImageIndex",
            "imagePayloadField",
            "eventCharacters",
            "encodedImageCharacters",
            "mappingOutcome",
            "mappedEventCount",
            "mappedPhase",
            "accepted",
            "retained",
            "observerCount",
            "signalType",
            "partialEvents",
            "finalEvents",
            "ignoredEvents",
            "mappingFailures");
    private static final Set<String> PROTOCOL_TOKEN_KEYS = Set.of(
            "checkpoint",
            "imageAction",
            "imagePayloadField",
            "mappingOutcome",
            "mappedPhase",
            "signalType");
    private static final Set<String> UPSTREAM_PROTOCOL_KEYS = Set.of(
            "upstreamEventName",
            "upstreamJsonType");
    private static final Set<String> KNOWN_UPSTREAM_PROTOCOL_TOKENS = Set.of(
            "message",
            "error",
            "image_generation.partial_image",
            "image_generation.completed",
            "image_edit.partial_image",
            "image_edit.completed",
            "response.image_generation_call.partial_image",
            "response.image_generation_call.completed",
            "response.completed",
            "response.failed");
    private static final Set<String> KNOWN_IMAGE_REQUEST_PATHS = Set.of(
            "/v1/images/generations",
            "/v1/images/edits");
    private static final Set<String> KNOWN_RESPONSE_CONTENT_TYPES = Set.of(
            MediaType.TEXT_EVENT_STREAM_VALUE,
            MediaType.APPLICATION_JSON_VALUE,
            MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            "other",
            "unavailable");
    private static final Set<String> NON_NEGATIVE_NUMBER_KEYS = Set.of(
            "bytes",
            "networkBytes",
            "parsedEvents",
            "eventCharacters",
            "encodedImageCharacters",
            "mappedEventCount",
            "observerCount",
            "partialEvents",
            "finalEvents",
            "ignoredEvents",
            "mappingFailures");

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

    static Map<String, Object> safeDetails(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        details.forEach((key, value) -> {
            if (ALLOWED_DETAIL_KEYS.contains(key)) {
                Object safeValue = safeValue(key, value);
                if (safeValue != RejectedValue.INSTANCE) {
                    safe.put(key, safeValue);
                }
            }
        });
        return Collections.unmodifiableMap(safe);
    }

    private static Object safeValue(String key, Object value) {
        if ("outputIndex".equals(key)) {
            return boundedWholeNumber(value, 0L, 9L);
        }
        if ("partialImageIndex".equals(key)) {
            return boundedWholeNumber(value, 0L, 2L);
        }
        if (NON_NEGATIVE_NUMBER_KEYS.contains(key)) {
            return boundedWholeNumber(value, 0L, Long.MAX_VALUE);
        }
        if (PROTOCOL_TOKEN_KEYS.contains(key)) {
            return protocolToken(value);
        }
        if (UPSTREAM_PROTOCOL_KEYS.contains(key)) {
            return knownUpstreamProtocolToken(value);
        }
        if ("requestPath".equals(key)) {
            return knownValue(value, KNOWN_IMAGE_REQUEST_PATHS, "custom");
        }
        if ("responseContentType".equals(key)) {
            return knownValue(value, KNOWN_RESPONSE_CONTENT_TYPES, "other");
        }
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Iterable<?> values) {
            return safeSubrequests(values);
        }
        return safe(String.valueOf(value));
    }

    private static Object boundedWholeNumber(
            Object value,
            long minimum,
            long maximum) {
        if (!(value instanceof Number number)) {
            return RejectedValue.INSTANCE;
        }
        double numeric = number.doubleValue();
        if (!Double.isFinite(numeric)
                || numeric != Math.rint(numeric)
                || numeric < minimum
                || numeric > maximum) {
            return RejectedValue.INSTANCE;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer) {
            return number.intValue();
        }
        return number.longValue();
    }

    private static String protocolToken(Object value) {
        if (value == null) {
            return "unavailable";
        }
        String token = String.valueOf(value);
        if (token.isBlank() || token.length() > 128) {
            return "unavailable";
        }
        for (int index = 0; index < token.length(); index++) {
            char current = token.charAt(index);
            if (!Character.isLetterOrDigit(current)
                    && current != '.'
                    && current != '_'
                    && current != '-') {
                return "unavailable";
            }
        }
        return token;
    }

    private static String knownUpstreamProtocolToken(Object value) {
        String token = protocolToken(value);
        if ("unavailable".equals(token)) {
            return token;
        }
        return KNOWN_UPSTREAM_PROTOCOL_TOKENS.contains(token)
                ? token
                : "unknown";
    }

    private static String knownValue(
            Object value,
            Set<String> allowedValues,
            String fallback) {
        if (value == null) {
            return "unavailable";
        }
        String normalized = String.valueOf(value);
        return allowedValues.contains(normalized) ? normalized : fallback;
    }

    /**
     * 子请求诊断只保留固定序号和请求标识是否存在，禁止上游可控标识或任意嵌套对象借诊断通道进入日志。
     */
    static List<Map<String, Object>> safeSubrequests(
            Iterable<?> values) {
        List<Map<String, Object>> safeValues = new ArrayList<>(10);
        for (Object value : values) {
            if (safeValues.size() == 10) {
                break;
            }
            if (!(value instanceof Map<?, ?> fields)) {
                continue;
            }
            Object outputIndex = fields.get("outputIndex");
            Object upstreamRequestId = fields.get("upstreamRequestId");
            boolean requestIdPresent = Boolean.TRUE.equals(
                    fields.get("requestIdPresent"))
                    || upstreamRequestId instanceof String requestId
                    && !requestId.isBlank();
            Object normalizedOutputIndex = boundedWholeNumber(
                    outputIndex, 0L, 9L);
            if (normalizedOutputIndex == RejectedValue.INSTANCE
                    || !requestIdPresent) {
                continue;
            }
            int normalizedIndex = ((Number) normalizedOutputIndex).intValue();
            safeValues.add(Map.of(
                    "outputIndex", normalizedIndex,
                    "requestIdPresent", true));
        }
        return List.copyOf(safeValues);
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

    /**
     * 区分需要从详情 Map 中完全删除的值与允许保留的空值，避免非法索引被误写成 null。
     */
    private enum RejectedValue {
        INSTANCE
    }
}
