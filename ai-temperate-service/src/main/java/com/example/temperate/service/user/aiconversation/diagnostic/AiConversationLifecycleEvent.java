package com.example.temperate.service.user.aiconversation.diagnostic;

/**
 * 定义生命周期日志允许输出的固定低敏字段，禁止调用方通过任意 Map 注入正文或账户数据。
 */
public record AiConversationLifecycleEvent(
        String outcome,
        String reactorSignal,
        String finishReason,
        String failureCode,
        String billingAction,
        String billingStatus,
        Boolean hasVisibleOutput,
        Boolean hasReportedUsage,
        Long emittedTextCharacters,
        Integer attempt,
        Long phaseDurationMs,
        Long queueDelayMs,
        String lifecycleStateBefore,
        String lifecycleStateAfter) {

    /**
     * 创建不携带附加字段的阶段事件。
     *
     * @return 空白固定字段事件
     */
    public static AiConversationLifecycleEvent empty() {
        return new AiConversationLifecycleEvent(
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    /**
     * 创建只携带阶段耗时的事件，供 AOP 和事务边界复用。
     *
     * @param durationMs 非负阶段耗时
     * @return 时序事件
     */
    public static AiConversationLifecycleEvent timing(long durationMs) {
        return new AiConversationLifecycleEvent(
                null, null, null, null, null, null,
                null, null, null, null, Math.max(0L, durationMs), null,
                null, null);
    }

    /**
     * 创建不包含第三方异常消息或堆栈内容的 AOP 失败事件。
     *
     * @param outcome 受控失败或未受控异常分类
     * @param failureCode 仅允许固定受控错误码，未知异常使用统一占位码
     * @param durationMs 方法或 Reactor 阶段耗时
     * @return 低敏失败诊断事件
     */
    public static AiConversationLifecycleEvent failure(
            String outcome,
            String failureCode,
            long durationMs) {
        return new AiConversationLifecycleEvent(
                outcome, null, null, failureCode, null, null,
                null, null, null, null, Math.max(0L, durationMs), null,
                null, null);
    }

    /**
     * 创建包含终态决策但不包含正文或账户余额的事件。
     *
     * @return 固定字段终态事件
     */
    public static AiConversationLifecycleEvent terminal(
            String outcome,
            String reactorSignal,
            String finishReason,
            String failureCode,
            String billingAction,
            String billingStatus,
            boolean hasVisibleOutput,
            boolean hasReportedUsage,
            long emittedTextCharacters) {
        return new AiConversationLifecycleEvent(
                outcome,
                reactorSignal,
                finishReason,
                failureCode,
                billingAction,
                billingStatus,
                hasVisibleOutput,
                hasReportedUsage,
                Math.max(0L, emittedTextCharacters),
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * 创建终态执行器排队或重试事件。
     *
     * @param attempt 当前尝试次数，可为空
     * @param queueDelayMs 排队耗时，可为空
     * @return 执行器诊断事件
     */
    public static AiConversationLifecycleEvent execution(
            Integer attempt,
            Long queueDelayMs) {
        return new AiConversationLifecycleEvent(
                null, null, null, null, null, null,
                null, null, null, attempt, null,
                queueDelayMs == null ? null : Math.max(0L, queueDelayMs),
                null,
                null);
    }

    /**
     * 创建终态所有权 CAS 前后的状态变化事件。
     *
     * @param reactorSignal 触发竞争的 Reactor 信号
     * @param stateBefore CAS 前状态
     * @param stateAfter CAS 后实际状态
     * @return 状态竞争诊断事件
     */
    public static AiConversationLifecycleEvent lifecycleState(
            String reactorSignal,
            String stateBefore,
            String stateAfter) {
        return new AiConversationLifecycleEvent(
                null, reactorSignal, null, null, null, null,
                null, null, null, null, null, null,
                stateBefore, stateAfter);
    }
}
