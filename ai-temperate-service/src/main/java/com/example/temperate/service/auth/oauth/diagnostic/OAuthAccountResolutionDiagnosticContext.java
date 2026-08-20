package com.example.temperate.service.auth.oauth.diagnostic;

/**
 * 保存一次 OAuth Provider 完成调用当前所处的账号解析阶段，供精确诊断切面关联失败位置。
 *
 * <p>上下文只保存固定枚举，不保存邮箱、Subject、Token 或其他请求数据；作用域关闭时恢复调用前状态，
 * 避免 Tomcat 工作线程复用导致不同请求之间串联诊断信息。</p>
 */
public final class OAuthAccountResolutionDiagnosticContext {

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private OAuthAccountResolutionDiagnosticContext() {
    }

    /**
     * 开启一个可嵌套的诊断作用域；调用方必须使用 try-with-resources 保证清理。
     */
    public static Scope open() {
        State previous = CURRENT.get();
        CURRENT.set(new State(null));
        return new Scope(previous);
    }

    /**
     * 标记即将进入的固定解析阶段；没有活动作用域时保持无操作，不改变业务调用行为。
     */
    public static void mark(Stage stage) {
        State current = CURRENT.get();
        if (current != null) {
            current.stage = stage;
        }
    }

    static Stage currentStage() {
        State current = CURRENT.get();
        return current == null ? null : current.stage;
    }

    /**
     * 限定 OAuth 可信身份进入本地账号解析后允许记录的四个非敏感阶段。
     */
    public enum Stage {
        SUBJECT_LOOKUP,
        EMAIL_LOOKUP,
        AUTH_CONTEXT_LOOKUP,
        FLOW_PERSISTENCE
    }

    /**
     * 在关闭时恢复嵌套调用之前的上下文，重复关闭不会影响当前线程状态。
     */
    public static final class Scope implements AutoCloseable {

        private final State previous;
        private boolean closed;

        private Scope(State previous) {
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

    private static final class State {

        private Stage stage;

        private State(Stage stage) {
            this.stage = stage;
        }
    }
}
