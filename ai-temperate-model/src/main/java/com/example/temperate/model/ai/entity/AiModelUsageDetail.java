package com.example.temperate.model.ai.entity;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 保存模型调用的幂等摘要、会话关联、预扣依据、倍率快照和最终结算差额。
 */
@Getter
@Setter
@NoArgsConstructor
public class AiModelUsageDetail {

    private Long id;
    private byte[] usageId;
    private byte[] conversationId;
    private Long conversationMessageId;
    private byte[] idempotencyKeyDigest;
    private String upstreamRequestId;
    private String vendorSnapshot;
    private Boolean stream;
    private Long estimatedPromptTokens;
    private Long maxOutputTokens;
    private BigDecimal inputRatioSnapshot;
    private BigDecimal cachedInputRatioSnapshot;
    private BigDecimal outputRatioSnapshot;
    private Long reservedQuotaMinor;
    private Long settlementDeltaMinor;
}
