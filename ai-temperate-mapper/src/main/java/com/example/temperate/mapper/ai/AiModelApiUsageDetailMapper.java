package com.example.temperate.mapper.ai;

import com.example.temperate.model.ai.entity.AiModelApiUsageDetail;
import com.example.temperate.model.ai.entity.AiModelApiUsageRefundCandidate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 该 Mapper 是来维护每条外部 API Usage 的一对一预扣详情和最终差额，并支持恢复任务批量写入退款差额。
 */
@Mapper
public interface AiModelApiUsageDetailMapper {

    int insert(AiModelApiUsageDetail detail);

    AiModelApiUsageDetail findByUsageId(@Param("usageId") byte[] usageId);

    int finalizeDetail(
            @Param("usageId") byte[] usageId,
            @Param("settlementDeltaMinor") long settlementDeltaMinor);

    int finalizeRefundsBatch(
            @Param("candidates") List<AiModelApiUsageRefundCandidate> candidates);
}
