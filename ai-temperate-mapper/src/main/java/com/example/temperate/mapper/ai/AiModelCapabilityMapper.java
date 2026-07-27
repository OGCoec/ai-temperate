package com.example.temperate.mapper.ai;

import com.example.temperate.model.ai.entity.AiModelCapability;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 提供 AI 模型能力的批量写入、事务内整组替换和按模型集合批量读取契约。
 *
 * <p>调用方必须先验证目标模型存在；能力删除只允许作为同一 PostgreSQL 本地事务中的整组替换步骤。</p>
 */
@Mapper
public interface AiModelCapabilityMapper {

    int insertBatch(@Param("capabilities") List<AiModelCapability> capabilities);

    int deleteByAiModelId(@Param("aiModelId") long aiModelId);

    List<AiModelCapability> findByAiModelId(@Param("aiModelId") long aiModelId);

    List<AiModelCapability> findByAiModelIds(@Param("aiModelIds") List<Long> aiModelIds);
}
