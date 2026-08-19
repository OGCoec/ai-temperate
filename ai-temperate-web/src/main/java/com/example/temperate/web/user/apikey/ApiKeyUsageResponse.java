package com.example.temperate.web.user.apikey;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.user.apikey.usage.ApiKeyUsageModels.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 该响应是来把 Service 的 API Key 调用记录转换为稳定 JSON 字段，并确保内部摘要、Key ID 和用量 ID 不进入 Web 契约。
 */
public record ApiKeyUsageResponse(
        Period period,
        Summary summary,
        List<Item> items,
        @Schema(
                nullable = true,
                minLength = 38,
                maxLength = 38,
                pattern = "^[A-Za-z0-9_-]{38}$")
        String nextCursor) {

    public ApiKeyUsageResponse {
        items = List.copyOf(items);
    }

    public static ApiKeyUsageResponse from(Page page) {
        return new ApiKeyUsageResponse(
                new Period(page.period().from(), page.period().to()),
                new Summary(
                        page.summary().requestCount(),
                        page.summary().promptTokens(),
                        page.summary().cachedPromptTokens(),
                        page.summary().uncachedPromptTokens(),
                        page.summary().completionTokens(),
                        page.summary().chargedQuotaMinor(),
                        page.summary().pendingRequestCount(),
                        page.summary().pendingReservedQuotaMinor()),
                page.items().stream().map(value -> new Item(
                        value.modelPublicId(),
                        value.modelName(),
                        value.vendor(),
                        value.stream(),
                        value.billingStatus(),
                        value.promptTokens(),
                        value.cachedPromptTokens(),
                        value.uncachedPromptTokens(),
                        value.completionTokens(),
                        value.chargedQuotaMinor(),
                        value.reservedQuotaMinor(),
                        value.finishReason(),
                        value.failureCode(),
                        value.createdAt(),
                        value.settledAt())).toList(),
                page.nextCursor());
    }

    /** 服务端实际采用的 UTC 半开时间区间。 */
    public record Period(String from, String to) {
    }

    /** 当前时间段的 Token、实际扣费与预扣中汇总。 */
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

    /** 单次调用的公开计量和状态元数据。 */
    public record Item(
            @Schema(
                    minLength = PublicIdCodec.ENCODED_LENGTH,
                    maxLength = PublicIdCodec.ENCODED_LENGTH,
                    pattern = PublicIdCodec.ENCODED_PATTERN,
                    example = "AAAAAAAAABc")
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
}
