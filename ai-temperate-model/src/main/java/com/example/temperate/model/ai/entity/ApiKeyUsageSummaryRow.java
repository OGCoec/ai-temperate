package com.example.temperate.model.ai.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 该查询行是来承载指定 API Key 与时间段的 Token、实际扣费和未结算预扣汇总，不包含 Key 摘要本身。
 */
@Getter
@Setter
@NoArgsConstructor
public class ApiKeyUsageSummaryRow {

    private Long requestCount;
    private Long promptTokens;
    private Long cachedPromptTokens;
    private Long completionTokens;
    private Long chargedQuotaMinor;
    private Long pendingRequestCount;
    private Long pendingReservedQuotaMinor;
}
