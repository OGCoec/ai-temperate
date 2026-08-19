package com.example.temperate.model.ai.entity;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 该查询行是来一次性承载 API Key 单次调用的核心用量、模型展示信息和预扣详情，内部用量 ID 仅用于稳定分页游标。
 */
@Getter
@Setter
@NoArgsConstructor
public class ApiKeyUsageRequestRow {

    private byte[] usageId;
    private Long aiModelId;
    private String modelName;
    private String vendor;
    private Boolean stream;
    private Integer billingStatus;
    private Long promptTokens;
    private Long cachedPromptTokens;
    private Long completionTokens;
    private Long chargedQuotaMinor;
    private Long reservedQuotaMinor;
    private String finishReason;
    private String failureCode;
    private OffsetDateTime createdAt;
    private OffsetDateTime settledAt;

    public byte[] getUsageId() {
        return usageId == null ? null : usageId.clone();
    }

    public void setUsageId(byte[] usageId) {
        this.usageId = usageId == null ? null : usageId.clone();
    }
}
