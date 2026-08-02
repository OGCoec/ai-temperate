package com.example.temperate.service.user.aiconversation.response;

/**
 * 定义把取消结算提交到有界后台执行器并以有限次数收敛 Usage 状态的业务边界。
 */
public interface AiConversationInterruptionFinalizer {

    void submit(
            AiConversationInterruptionCommand command,
            AiConversationRequestLifecycle lifecycle);
}
