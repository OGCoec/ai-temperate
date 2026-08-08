package com.example.temperate.service.user.aiconversation.image;

/**
 * 描述单个供应商图片输出槽的成本证据是否完整，供异步终态决定结算或保留预扣待对账。
 */
public enum AiConversationImageMeteringStatus {
    COMPLETE,
    MISSING_COST,
    INVALID_COST
}
