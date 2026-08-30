package com.example.temperate.web.auth.diagnostic.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 为一次 Servlet API 请求累计认证门禁阶段耗时，并生成浏览器 Network 可读取的 Server-Timing 响应头。
 *
 * <p>该对象只在请求属性中保存单调时钟和有限错误码，不保存 Token、Cookie、IP、设备标识、请求体或响应体。
 * Filter 负责初始化总时钟，各拦截器只记录自己的阶段，直接写响应的拦截器必须在提交响应体前调用响应头输出。</p>
 */
public final class AuthRequestTiming {

    public static final String SERVER_TIMING_HEADER = "Server-Timing";
    public static final String ERROR_CODE_ATTRIBUTE =
            AuthRequestTiming.class.getName() + ".errorCode";
    public static final String CLEAR_COOKIES_ATTRIBUTE =
            AuthRequestTiming.class.getName() + ".clearCookies";
    private static final String STATE_ATTRIBUTE =
            AuthRequestTiming.class.getName() + ".state";
    private static final Pattern SAFE_ERROR_CODE =
            Pattern.compile("^[A-Z0-9_]{1,80}$");

    private AuthRequestTiming() {}

    /**
     * 定义能够安全暴露到 Server-Timing 的固定阶段，禁止使用客户端输入动态创建指标名。
     */
    public enum Stage {
        RISK("risk"),
        WEBRTC("webrtc"),
        SESSION("session"),
        PREAUTH_BINDING("preauth"),
        CONTROLLER("controller");

        private final String metricName;

        Stage(String metricName) {
            this.metricName = metricName;
        }
    }

    /** 初始化一次请求的总时钟和响应头开关，只允许最外层诊断 Filter 调用。 */
    public static void initialize(
            HttpServletRequest request,
            boolean serverTimingEnabled) {
        request.setAttribute(
                STATE_ATTRIBUTE,
                new TimingState(System.nanoTime(), serverTimingEnabled));
    }

    /** 在请求属性中启动固定阶段的单调计时；诊断未初始化时保持无副作用。 */
    public static void begin(HttpServletRequest request, Stage stage) {
        TimingState state = state(request);
        if (state == null) {
            return;
        }
        state.startedNanos.putIfAbsent(stage, System.nanoTime());
    }

    /** 完成固定阶段计时并返回毫秒值，重复完成只读取第一次得到的结果。 */
    public static long complete(HttpServletRequest request, Stage stage) {
        TimingState state = state(request);
        if (state == null) {
            return 0L;
        }
        Long startedNanos = state.startedNanos.remove(stage);
        if (startedNanos == null) {
            return state.durationsMillis.getOrDefault(stage, 0L);
        }
        long elapsed = elapsedMillis(startedNanos);
        // 同一逻辑请求发生 ASYNC 二次分派时累计阶段耗时，禁止用复用检查的极短耗时覆盖初始门禁成本。
        return state.durationsMillis.merge(stage, elapsed, Long::sum);
    }

    /** 写入由可信服务端代码计算的阶段耗时，禁止把客户端提交的数字写入该方法。 */
    public static void recordMillis(
            HttpServletRequest request,
            Stage stage,
            long durationMillis) {
        TimingState state = state(request);
        if (state == null) {
            return;
        }
        state.durationsMillis.put(stage, Math.max(0L, durationMillis));
    }

    /** 读取已经完成的固定阶段耗时，不存在时返回空值而不是伪造零耗时。 */
    public static OptionalLong durationMillis(
            HttpServletRequest request,
            Stage stage) {
        TimingState state = state(request);
        if (state == null || !state.durationsMillis.containsKey(stage)) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(state.durationsMillis.get(stage));
    }

    /** 返回从诊断 Filter 入站到当前时刻的请求总耗时。 */
    public static long totalMillis(HttpServletRequest request) {
        TimingState state = state(request);
        return state == null ? 0L : elapsedMillis(state.requestStartedNanos);
    }

    /** 保存经过白名单格式限制的业务错误码，不保存异常消息或响应体。 */
    public static void recordErrorCode(HttpServletRequest request, String errorCode) {
        String normalized = errorCode == null
                ? "unavailable"
                : errorCode.trim().toUpperCase(Locale.ROOT);
        request.setAttribute(
                ERROR_CODE_ATTRIBUTE,
                SAFE_ERROR_CODE.matcher(normalized).matches()
                        ? normalized
                        : "unavailable");
    }

    /** 返回当前请求的安全错误码，尚未裁决时返回 unavailable。 */
    public static String errorCode(HttpServletRequest request) {
        Object value = request.getAttribute(ERROR_CODE_ATTRIBUTE);
        return value instanceof String code ? code : "unavailable";
    }

    /**
     * 在响应提交前写入固定阶段和总耗时；响应已提交或开关关闭时不得尝试改写响应。
     */
    public static void writeServerTiming(
            HttpServletRequest request,
            HttpServletResponse response) {
        TimingState state = state(request);
        if (state == null || !state.serverTimingEnabled || response.isCommitted()) {
            return;
        }
        StringBuilder header = new StringBuilder();
        for (Stage stage : Stage.values()) {
            Long duration = state.durationsMillis.get(stage);
            if (duration == null) {
                continue;
            }
            if (!header.isEmpty()) {
                header.append(", ");
            }
            header.append(stage.metricName).append(";dur=").append(duration);
        }
        if (!header.isEmpty()) {
            header.append(", ");
        }
        header.append("total;dur=").append(totalMillis(request));
        response.setHeader(SERVER_TIMING_HEADER, header.toString());
    }

    private static TimingState state(HttpServletRequest request) {
        Object value = request.getAttribute(STATE_ATTRIBUTE);
        return value instanceof TimingState timingState ? timingState : null;
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static final class TimingState {

        private final long requestStartedNanos;
        private final boolean serverTimingEnabled;
        private final Map<Stage, Long> startedNanos = new ConcurrentHashMap<>();
        private final Map<Stage, Long> durationsMillis = new ConcurrentHashMap<>();

        private TimingState(long requestStartedNanos, boolean serverTimingEnabled) {
            this.requestStartedNanos = requestStartedNanos;
            this.serverTimingEnabled = serverTimingEnabled;
        }
    }
}
