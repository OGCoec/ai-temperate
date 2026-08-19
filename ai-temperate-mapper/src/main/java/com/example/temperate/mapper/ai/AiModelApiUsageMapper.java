package com.example.temperate.mapper.ai;

import com.example.temperate.model.ai.entity.AiModelApiUsage;
import com.example.temperate.model.ai.entity.AiModelApiUsageRefundCandidate;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 该 Mapper 是来创建外部 API 预扣用量、以条件状态更新保证幂等结算，并批量锁定和退款陈旧 RESERVED 记录。
 */
@Mapper
public interface AiModelApiUsageMapper {

    int insert(AiModelApiUsage usage);

    AiModelApiUsage findByIdForUpdate(@Param("usageId") byte[] usageId);

    int settle(
            @Param("usageId") byte[] usageId,
            @Param("expectedBillingStatus") int expectedBillingStatus,
            @Param("billingStatus") int billingStatus,
            @Param("promptTokens") Long promptTokens,
            @Param("completionTokens") Long completionTokens,
            @Param("cachedPromptTokens") Long cachedPromptTokens,
            @Param("chargedQuotaMinor") Long chargedQuotaMinor,
            @Param("finishReason") String finishReason,
            @Param("failureCode") String failureCode,
            @Param("settledAt") OffsetDateTime settledAt);

    List<AiModelApiUsageRefundCandidate> findExpiredReservationsForUpdate(
            @Param("reservedStatus") int reservedStatus,
            @Param("createdBefore") OffsetDateTime createdBefore,
            @Param("limit") int limit);

    int markRefundedBatch(
            @Param("candidates") List<AiModelApiUsageRefundCandidate> candidates,
            @Param("reservedStatus") int reservedStatus,
            @Param("refundedStatus") int refundedStatus,
            @Param("failureCode") String failureCode,
            @Param("settledAt") OffsetDateTime settledAt);
}
