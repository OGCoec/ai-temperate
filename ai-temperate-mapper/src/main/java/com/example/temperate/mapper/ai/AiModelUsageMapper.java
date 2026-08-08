package com.example.temperate.mapper.ai;

import com.example.temperate.model.ai.entity.AiModelUsage;
import com.example.temperate.model.ai.entity.AiModelUsageRefundCandidate;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 提供模型用量预扣记录创建、行级锁定和最终状态更新的 MyBatis 持久化契约。
 */
@Mapper
public interface AiModelUsageMapper {

    int insert(AiModelUsage usage);

    AiModelUsage findById(@Param("usageId") byte[] usageId);

    AiModelUsage findByIdForUpdate(@Param("usageId") byte[] usageId);

    int settle(
            @Param("usageId") byte[] usageId,
            @Param("expectedBillingStatus") int expectedBillingStatus,
            @Param("billingStatus") int billingStatus,
            @Param("promptTokens") Long promptTokens,
            @Param("completionTokens") Long completionTokens,
            @Param("cachedPromptTokens") Long cachedPromptTokens,
            @Param("reasoningTokens") Long reasoningTokens,
            @Param("providerCostTicks") Long providerCostTicks,
            @Param("chargedQuotaMinor") Long chargedQuotaMinor,
            @Param("finishReason") String finishReason,
            @Param("failureCode") String failureCode,
            @Param("settledAt") OffsetDateTime settledAt);

    int markExpiredReservationsForReconciliation(
            @Param("reservedStatus") int reservedStatus,
            @Param("reconcileStatus") int reconcileStatus,
            @Param("createdBefore") OffsetDateTime createdBefore,
            @Param("batchSize") int batchSize,
            @Param("failureCode") String failureCode,
            @Param("settledAt") OffsetDateTime settledAt);

    List<AiModelUsageRefundCandidate>
            findSystemFailureRefundCandidatesForUpdate(
                    @Param("reconcileStatus") int reconcileStatus,
                    @Param("failureCodes") List<String> failureCodes,
                    @Param("batchSize") int batchSize);

    int markHistoricalSystemFailuresRefunded(
            @Param("candidates") List<AiModelUsageRefundCandidate> candidates,
            @Param("reconcileStatus") int reconcileStatus,
            @Param("refundedStatus") int refundedStatus,
            @Param("settledAt") OffsetDateTime settledAt);
}
