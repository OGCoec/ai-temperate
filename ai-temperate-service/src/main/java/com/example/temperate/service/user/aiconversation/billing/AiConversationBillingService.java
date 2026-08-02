package com.example.temperate.service.user.aiconversation.billing;

/**
 * 定义会话创建、额度周期激活、最大额度预扣和幂等占位的短事务边界。
 */
public interface AiConversationBillingService {

    AiConversationReservation reserve(AiConversationReservationCommand command);
}
