package com.example.temperate.service.risk.observability;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 保存一次网络风险校验的非敏感诊断关联信息，并在同步或显式跨线程作用域结束后恢复原上下文。
 *
 * <p>该上下文只承载 traceId、调用序号、分派类型和阶段，不保存请求、Token、Cookie、IP、设备标识、
 * 会话引用或 Redis Key；调用方必须使用可关闭作用域，避免线程池复用时发生串号。</p>
 */
public final class NetworkRiskDiagnosticContext {

    private static final String ABSENT = "absent";
    private static final Pattern SAFE_VALUE =
            Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Snapshot EMPTY =
            new Snapshot(ABSENT, 0, ABSENT, ABSENT);
    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    private NetworkRiskDiagnosticContext() {
    }

    /**
     * 创建已净化的不可变快照，供 Reactor 回调显式恢复原请求的诊断关联信息。
     */
    public static Snapshot snapshot(
            String traceId,
            int invocationNo,
            String dispatcherType,
            String phase) {
        return new Snapshot(
                safeTraceId(traceId),
                Math.max(0, invocationNo),
                safeValue(dispatcherType),
                safeValue(phase));
    }

    /**
     * 打开当前线程的诊断作用域；关闭时恢复进入前的值，以支持嵌套调用并阻止线程池污染。
     */
    public static Scope open(
            String traceId,
            int invocationNo,
            String dispatcherType,
            String phase) {
        return open(snapshot(traceId, invocationNo, dispatcherType, phase));
    }

    /**
     * 使用预先捕获的快照打开作用域，适用于受控的 Reactor 或异步回调边界。
     */
    public static Scope open(Snapshot snapshot) {
        Snapshot previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNullElse(snapshot, EMPTY));
        return new Scope(previous);
    }

    /**
     * 在快照作用域内执行回调并可靠恢复线程原值，禁止调用方手工设置或清理 ThreadLocal。
     */
    public static <T> T call(Snapshot snapshot, Supplier<T> callback) {
        Objects.requireNonNull(callback, "callback");
        try (Scope ignored = open(snapshot)) {
            return callback.get();
        }
    }

    /**
     * 返回当前线程的诊断快照；未处于作用域时返回稳定的 absent 快照而不是 null。
     */
    public static Snapshot current() {
        return Objects.requireNonNullElse(CURRENT.get(), EMPTY);
    }

    private static String safeTraceId(String value) {
        String sanitized = safeValue(value);
        return ABSENT.equals(sanitized) ? ABSENT : sanitized;
    }

    private static String safeValue(String value) {
        if (value == null || value.isBlank()) {
            return ABSENT;
        }
        return SAFE_VALUE.matcher(value).matches() ? value : ABSENT;
    }

    /**
     * 表示可跨线程显式传递的最小诊断快照，不包含任何业务凭据或网络身份信息。
     */
    public record Snapshot(
            String traceId,
            int invocationNo,
            String dispatcherType,
            String phase) {

        public Snapshot {
            traceId = safeTraceId(traceId);
            invocationNo = Math.max(0, invocationNo);
            dispatcherType = safeValue(dispatcherType);
            phase = safeValue(phase);
        }
    }

    /**
     * 负责在作用域关闭时恢复此前上下文；重复关闭不会再次修改线程状态。
     */
    public static final class Scope implements AutoCloseable {

        private final Snapshot previous;
        private boolean closed;

        private Scope(Snapshot previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
                return;
            }
            CURRENT.set(previous);
        }
    }
}
