package com.example.temperate.mapper.ai;

import com.example.temperate.model.ai.entity.ApiKeyUsageRequestRow;
import com.example.temperate.model.ai.entity.ApiKeyUsageSummaryRow;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 该只读 Mapper 是来按已验证 API Key 摘要和半开时间区间汇总、分页查询单次模型调用，不承担计费写入或所有权判断。
 */
@Mapper
public interface ApiKeyUsageQueryMapper {

    ApiKeyUsageSummaryRow summarize(
            @Param("keyDigest") byte[] keyDigest,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    List<ApiKeyUsageRequestRow> findPage(
            @Param("keyDigest") byte[] keyDigest,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            @Param("cursorCreatedAt") OffsetDateTime cursorCreatedAt,
            @Param("cursorId") byte[] cursorId,
            @Param("limit") int limit);
}
