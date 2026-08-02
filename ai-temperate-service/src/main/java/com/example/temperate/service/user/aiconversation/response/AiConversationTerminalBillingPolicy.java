package com.example.temperate.service.user.aiconversation.response;

import com.example.temperate.service.user.aiconversation.billing.AiConversationReservation;
import com.example.temperate.service.user.aiconversation.model.AiConversationUsage;

/**
 * 统一判定系统失败和客户端取消应当全退、按可信用量结算还是按已展示文本估算结算。
 */
public interface AiConversationTerminalBillingPolicy {

    /**
     * 将平台或上游责任失败映射为全额退款，并保留稳定失败码供审计与前端展示。
     */
    AiConversationTerminalBillingDecision systemFailure(Throwable failure);

    /**
     * 按最终 Usage、已展示输出和空输出的优先级决定用户主动取消时的唯一计费终态。
     */
    AiConversationTerminalBillingDecision clientCancellation(
            AiConversationReservation reservation,
            AiConversationUsage reportedUsage,
            String deliveredAssistantText);
}
