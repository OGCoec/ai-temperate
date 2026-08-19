package com.example.temperate.service.user.apikey.usage;

import java.util.List;

/**
 * 该模型集合是来定义 API Key 调用记录从 Service 到 Web 层的稳定只读契约，所有大整数已转换为十进制字符串。
 */
public final class ApiKeyUsageModels {

    private ApiKeyUsageModels() {
    }

    /** 查询实际采用的 UTC 半开时间区间。 */
    public record Period(String from, String to) {
    }

    /** 所选时间段的调用、Token、实际扣费与未结算预扣汇总。 */
    public record Summary(
            String requestCount,
            String promptTokens,
            String cachedPromptTokens,
            String uncachedPromptTokens,
            String completionTokens,
            String chargedQuotaMinor,
            String pendingRequestCount,
            String pendingReservedQuotaMinor) {
    }

    /** 单次 API 模型调用的公开元数据，不包含 Key、摘要或请求与回答正文。 */
    public record Item(
            String modelPublicId,
            String modelName,
            String vendor,
            boolean stream,
            String billingStatus,
            String promptTokens,
            String cachedPromptTokens,
            String uncachedPromptTokens,
            String completionTokens,
            String chargedQuotaMinor,
            String reservedQuotaMinor,
            String finishReason,
            String failureCode,
            String createdAt,
            String settledAt) {
    }

    /** 同一固定时间段的汇总、当前明细页和下一页游标。 */
    public record Page(
            Period period,
            Summary summary,
            List<Item> items,
            String nextCursor) {

        public Page {
            items = List.copyOf(items);
        }
    }
}
