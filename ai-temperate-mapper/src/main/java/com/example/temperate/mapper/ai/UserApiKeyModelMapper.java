package com.example.temperate.mapper.ai;

import com.example.temperate.model.ai.entity.ApiKeyModelGrantView;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 该 Mapper 是来批量查询、软撤销和 UPSERT 恢复 API Key 模型授权，批次内不执行逐条 I/O，也不提供物理删除。
 */
@Mapper
public interface UserApiKeyModelMapper {

    List<Long> findActiveModelIds(@Param("userApiKeyId") long userApiKeyId);

    List<ApiKeyModelGrantView> findActiveModelDetails(
            @Param("userApiKeyId") long userApiKeyId);

    int revokeMissing(
            @Param("userApiKeyId") long userApiKeyId,
            @Param("retainedModelIds") List<Long> retainedModelIds,
            @Param("revokedAt") OffsetDateTime revokedAt);

    int revokeAll(
            @Param("userApiKeyId") long userApiKeyId,
            @Param("revokedAt") OffsetDateTime revokedAt);

    int upsertActiveBatch(
            @Param("userApiKeyId") long userApiKeyId,
            @Param("modelIds") List<Long> modelIds,
            @Param("now") OffsetDateTime now);
}
