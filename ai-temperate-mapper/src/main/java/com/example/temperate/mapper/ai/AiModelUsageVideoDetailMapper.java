package com.example.temperate.mapper.ai;

import com.example.temperate.model.ai.entity.AiModelUsageVideoDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 提供视频模型用量计费快照的创建与按 usage ID 精确读取契约。
 *
 * <p>逻辑关联完整性由预扣事务和孤儿检查 SQL 共同维护，不在数据库中建立物理外键。</p>
 */
@Mapper
public interface AiModelUsageVideoDetailMapper {

    int insert(AiModelUsageVideoDetail detail);

    AiModelUsageVideoDetail findByUsageId(@Param("usageId") byte[] usageId);
}
