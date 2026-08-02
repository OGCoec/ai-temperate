package com.example.temperate.service.user.aiconversation.diagnostic.impl;

import com.example.temperate.service.user.aiconversation.config.AiConversationLifecycleDiagnosticsProperties;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleDiagnosticService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleEvent;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleTraceContext;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 依据稳定采样输出固定字段生命周期日志，并以可恢复的 ThreadLocal/MDC 作用域跨越受控异步结算调用。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.lifecycle-diagnostics",
        name = "enabled",
        havingValue = "true")
public final class AiConversationLifecycleDiagnosticServiceImpl
        implements AiConversationLifecycleDiagnosticService {

    public static final String CLIENT_REQUEST_MDC_KEY = "aiClientRequestId";
    public static final String USAGE_MDC_KEY = "aiUsagePublicId";
    public static final String CONVERSATION_MDC_KEY = "aiConversationPublicId";
    public static final String MODEL_MDC_KEY = "aiModelPublicId";
    public static final String STARTED_NANOS_MDC_KEY = "aiRequestStartedNanos";
    public static final String SAMPLED_MDC_KEY = "aiLifecycleDiagnosticSampled";
    private static final String TRACE_MDC_KEY = "traceId";
    private static final Logger LOGGER = LoggerFactory.getLogger(
            AiConversationLifecycleDiagnosticServiceImpl.class);

    private final AiConversationLifecycleDiagnosticsProperties properties;
    private final ThreadLocal<AiConversationLifecycleTraceContext> local =
            new ThreadLocal<>();

    public AiConversationLifecycleDiagnosticServiceImpl(
            AiConversationLifecycleDiagnosticsProperties properties) {
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public void record(
            AiConversationLifecycleTraceContext context,
            String phase,
            AiConversationLifecycleEvent details) {
        AiConversationLifecycleTraceContext safeContext = context == null
                ? AiConversationLifecycleTraceContext.unavailable()
                : context;
        if (!properties.shouldSample(
                safeContext.clientRequestId(),
                fallbackCorrelation(safeContext))) {
            return;
        }
        AiConversationLifecycleEvent safeDetails = details == null
                ? AiConversationLifecycleEvent.empty() : details;
        LOGGER.info(
                "event=ai_conversation_lifecycle traceId={} clientRequestId={} "
                        + "usagePublicId={} conversationPublicId={} modelPublicId={} "
                        + "phase={} outcome={} reactorSignal={} finishReason={} "
                        + "failureCode={} billingAction={} billingStatus={} "
                        + "hasVisibleOutput={} hasReportedUsage={} "
                        + "emittedTextCharacters={} attempt={} elapsedMs={} "
                        + "phaseDurationMs={} queueDelayMs={} "
                        + "lifecycleStateBefore={} lifecycleStateAfter={} thread={}",
                safeIdentifier(safeContext.traceId()),
                safeIdentifier(safeContext.clientRequestId()),
                safeIdentifier(safeContext.usagePublicId()),
                safeIdentifier(safeContext.conversationPublicId()),
                safeIdentifier(safeContext.modelPublicId()),
                safeEnum(phase),
                safeEnum(safeDetails.outcome()),
                safeEnum(safeDetails.reactorSignal()),
                safeEnum(safeDetails.finishReason()),
                safeEnum(safeDetails.failureCode()),
                safeEnum(safeDetails.billingAction()),
                safeEnum(safeDetails.billingStatus()),
                safeBoolean(safeDetails.hasVisibleOutput()),
                safeBoolean(safeDetails.hasReportedUsage()),
                safeLong(safeDetails.emittedTextCharacters()),
                safeInteger(safeDetails.attempt()),
                safeElapsedMillis(safeContext.requestStartedNanos()),
                safeLong(safeDetails.phaseDurationMs()),
                safeLong(safeDetails.queueDelayMs()),
                safeEnum(safeDetails.lifecycleStateBefore()),
                safeEnum(safeDetails.lifecycleStateAfter()),
                safeThreadName());
    }

    @Override
    public AiConversationLifecycleTraceContext currentContext() {
        AiConversationLifecycleTraceContext context = local.get();
        return context == null
                ? AiConversationLifecycleTraceContext.unavailable()
                : context;
    }

    @Override
    public <T> T withContext(
            AiConversationLifecycleTraceContext context,
            Supplier<T> action) {
        Objects.requireNonNull(action);
        // 诊断关闭时保持真正的空路径，不创建 ThreadLocal/MDC 上下文，也不改变原有异步调用行为。
        if (!properties.enabled()) {
            return action.get();
        }
        AiConversationLifecycleTraceContext safeContext = context == null
                ? AiConversationLifecycleTraceContext.unavailable()
                : context;
        AiConversationLifecycleTraceContext previous = local.get();
        String previousTraceId = MDC.get(TRACE_MDC_KEY);
        String previousClientRequestId = MDC.get(CLIENT_REQUEST_MDC_KEY);
        String previousUsage = MDC.get(USAGE_MDC_KEY);
        String previousConversation = MDC.get(CONVERSATION_MDC_KEY);
        String previousModel = MDC.get(MODEL_MDC_KEY);
        String previousStartedNanos = MDC.get(STARTED_NANOS_MDC_KEY);
        String previousSampled = MDC.get(SAMPLED_MDC_KEY);
        local.set(safeContext);
        putContext(safeContext);
        try {
            return action.get();
        } finally {
            restoreLocal(previous);
            restoreMdc(TRACE_MDC_KEY, previousTraceId);
            restoreMdc(CLIENT_REQUEST_MDC_KEY, previousClientRequestId);
            restoreMdc(USAGE_MDC_KEY, previousUsage);
            restoreMdc(CONVERSATION_MDC_KEY, previousConversation);
            restoreMdc(MODEL_MDC_KEY, previousModel);
            restoreMdc(STARTED_NANOS_MDC_KEY, previousStartedNanos);
            restoreMdc(SAMPLED_MDC_KEY, previousSampled);
        }
    }

    @Override
    public void withContext(
            AiConversationLifecycleTraceContext context,
            Runnable action) {
        withContext(context, () -> {
            action.run();
            return null;
        });
    }

    private void putContext(AiConversationLifecycleTraceContext context) {
        MDC.put(TRACE_MDC_KEY, safeIdentifier(context.traceId()));
        MDC.put(CLIENT_REQUEST_MDC_KEY,
                safeIdentifier(context.clientRequestId()));
        MDC.put(USAGE_MDC_KEY, safeIdentifier(context.usagePublicId()));
        MDC.put(CONVERSATION_MDC_KEY,
                safeIdentifier(context.conversationPublicId()));
        MDC.put(MODEL_MDC_KEY, safeIdentifier(context.modelPublicId()));
        MDC.put(STARTED_NANOS_MDC_KEY,
                Long.toString(context.requestStartedNanos()));
        MDC.put(SAMPLED_MDC_KEY, Boolean.toString(properties.shouldSample(
                context.clientRequestId(), fallbackCorrelation(context))));
    }

    private static String fallbackCorrelation(
            AiConversationLifecycleTraceContext context) {
        return !"unavailable".equals(context.traceId())
                ? context.traceId() : context.usagePublicId();
    }

    private void restoreLocal(AiConversationLifecycleTraceContext previous) {
        if (previous == null) {
            local.remove();
        } else {
            local.set(previous);
        }
    }

    private static void restoreMdc(String key, String previous) {
        if (previous == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previous);
        }
    }

    private static String safeIdentifier(String value) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        return normalized.length() <= 128
                && normalized.matches("^[A-Za-z0-9_.:-]+$")
                ? normalized : "unavailable";
    }

    private static String safeEnum(String value) {
        if (value == null || value.isBlank()) {
            return "unavailable";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.length() <= 64
                && normalized.matches("^[A-Z][A-Z0-9_]*$")
                ? normalized : "unavailable";
    }

    private static String safeBoolean(Boolean value) {
        return value == null ? "unavailable" : value.toString();
    }

    private static String safeLong(Long value) {
        return value == null ? "unavailable" : Long.toString(Math.max(0L, value));
    }

    private static String safeInteger(Integer value) {
        return value == null ? "unavailable" : Integer.toString(Math.max(0, value));
    }

    private static String safeElapsedMillis(long startedNanos) {
        return startedNanos <= 0L
                ? "unavailable"
                : Long.toString(Math.max(
                        0L,
                        (System.nanoTime() - startedNanos) / 1_000_000L));
    }

    private static String safeThreadName() {
        String name = Thread.currentThread().getName();
        return name.length() <= 64 ? name : name.substring(0, 64);
    }
}
