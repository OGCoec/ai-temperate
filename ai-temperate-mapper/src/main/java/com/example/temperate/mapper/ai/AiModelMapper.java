package com.example.temperate.mapper.ai;

import com.example.temperate.model.ai.entity.AiModel;
import com.example.temperate.model.ai.entity.AiModelSearchTokenUpdate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 提供 AI 模型目录的有界查询、新增、乐观锁字段编辑和启停持久化契约。
 *
 * <p>该 Mapper 不提供模型物理删除；字段编辑使用固定列和行版本，批量状态修改必须由调用方先验证全部模型存在。</p>
 */
@Mapper
public interface AiModelMapper {

    int insert(AiModel model);

    int updateEditable(
            @Param("model") AiModel model,
            @Param("expectedVersion") long expectedVersion);

    AiModel findById(@Param("id") long id);

    List<AiModel> findByIds(@Param("ids") List<Long> ids);

    List<AiModel> findPage(
            @Param("keyword") String keyword,
            @Param("enabled") Boolean enabled);

    List<AiModel> findEnabled(@Param("limit") int limit);

    int countByNormalizedModelName(@Param("modelName") String modelName);

    int updateEnabled(
            @Param("id") long id,
            @Param("enabled") boolean enabled);

    int updateEnabledBatch(
            @Param("ids") List<Long> ids,
            @Param("enabled") boolean enabled);

    Boolean findEnabledById(@Param("id") long id);

    List<AiModel> findTokenBackfillPage(
            @Param("afterId") long afterId,
            @Param("limit") int limit);

    int updateSearchTokensBatch(
            @Param("updates") List<AiModelSearchTokenUpdate> updates);
}
