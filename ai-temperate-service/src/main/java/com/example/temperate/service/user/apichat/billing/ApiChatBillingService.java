package com.example.temperate.service.user.apichat.billing;

import com.example.temperate.service.user.apichat.ValidatedApiChatRequest;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import java.math.BigDecimal;

/**
 * 该服务是来在短 PostgreSQL 事务中完成外部 API 预扣、最终 Usage 结算、取消估算和系统失败退款，网络调用必须位于事务之外。
 */
public interface ApiChatBillingService {

    Reservation reserve(ApiKeyPrincipal principal, ValidatedApiChatRequest request);

    void settle(Reservation reservation, Usage usage, String finishReason);

    void settleCancellationEstimate(Reservation reservation, long emittedUtf8Bytes);

    void refundSystemFailure(Reservation reservation, String failureCode);

    /** 预扣结果冻结本次调用所需账号、模型倍率和额度，不写入数据库价格倍率快照。 */
    record Reservation(
            long usageId,
            long loginIdentityId,
            long apiKeyId,
            long reservedMinor,
            long estimatedPromptTokens,
            BigDecimal inputRatio,
            BigDecimal cachedInputRatio,
            BigDecimal outputRatio) {
    }

    /** 上游最终 Usage 必须满足 cachedPromptTokens 不超过 promptTokens。 */
    record Usage(long promptTokens, long completionTokens, long cachedPromptTokens) {
    }
}
