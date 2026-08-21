package com.example.temperate.service.user.membership.payment.observability;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * 该上下文是来为会员支付调度与批处理建立安全 Trace，并在复用线程结束本轮工作时恢复原 MDC，避免跨任务串联日志。
 */
public final class MembershipPaymentTraceContext implements AutoCloseable {

    private static final String TRACE_KEY = "traceId";
    private static final String UNAVAILABLE = "unavailable";
    private static final Pattern SAFE_TRACE_ID =
            Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    private final String previousTraceId;
    private final String traceId;
    private final boolean installed;

    private MembershipPaymentTraceContext() {
        this.previousTraceId = MDC.get(TRACE_KEY);
        if (safe(previousTraceId)) {
            this.traceId = previousTraceId;
            this.installed = false;
        } else {
            this.traceId = UUID.randomUUID().toString();
            this.installed = true;
            MDC.put(TRACE_KEY, traceId);
        }
    }

    public static MembershipPaymentTraceContext open() {
        return new MembershipPaymentTraceContext();
    }

    public static String currentTraceId() {
        String current = MDC.get(TRACE_KEY);
        return safe(current) ? current : UNAVAILABLE;
    }

    public String traceId() {
        return traceId;
    }

    @Override
    public void close() {
        if (!installed) {
            return;
        }
        if (previousTraceId == null) {
            MDC.remove(TRACE_KEY);
        } else {
            MDC.put(TRACE_KEY, previousTraceId);
        }
    }

    private static boolean safe(String value) {
        return value != null && SAFE_TRACE_ID.matcher(value).matches();
    }
}
