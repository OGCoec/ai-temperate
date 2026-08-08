package com.example.temperate.mapper.ai;

import com.example.temperate.model.ai.entity.AiModelUsageDetail;
import com.example.temperate.model.ai.entity.AiModelUsageRefundCandidate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 提供模型用量详情创建、幂等定位和最终消息关联更新的 MyBatis 持久化契约。
 */
@Mapper
public interface AiModelUsageDetailMapper {

    int acquireIdempotencyLock(@Param("lockKey") long lockKey);

    int insert(AiModelUsageDetail detail);

    AiModelUsageDetail findByIdempotencyDigest(
            @Param("idempotencyKeyDigest") byte[] idempotencyKeyDigest);

    AiModelUsageDetail findByUsageId(@Param("usageId") byte[] usageId);

    int finalizeDetail(
            @Param("usageId") byte[] usageId,
            @Param("conversationMessageId") Long conversationMessageId,
            @Param("upstreamRequestId") String upstreamRequestId,
            @Param("settlementDeltaMinor") Long settlementDeltaMinor);

    int finalizeHistoricalRefunds(
            @Param("candidates") List<AiModelUsageRefundCandidate> candidates);
}
