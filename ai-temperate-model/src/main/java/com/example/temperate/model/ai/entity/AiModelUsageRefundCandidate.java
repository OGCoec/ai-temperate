package com.example.temperate.model.ai.entity;

/**
 * 表示一条经过数据库资格过滤并锁定的历史系统失败退款候选，供同一事务内批量恢复额度。
 */
public record AiModelUsageRefundCandidate(
        byte[] usageId,
        long loginIdentityId,
        long reservedQuotaMinor) {

    public AiModelUsageRefundCandidate {
        usageId = usageId.clone();
        if (loginIdentityId <= 0L || reservedQuotaMinor <= 0L) {
            throw new IllegalArgumentException(
                    "Historical AI refund candidate is invalid.");
        }
    }

    @Override
    public byte[] usageId() {
        return usageId.clone();
    }
}
