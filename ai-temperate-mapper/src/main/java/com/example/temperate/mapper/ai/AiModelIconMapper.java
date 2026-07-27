package com.example.temperate.mapper.ai;

import com.example.temperate.model.ai.entity.AiModelIcon;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 提供 AI 模型图标资源的分页查询、受控写入、行锁和逻辑引用检查。
 *
 * <p>模型表不使用物理外键，因此模型写入通过共享锁确认图标存在，图标删除通过排他锁和引用统计
 * 与并发模型写入串行化。</p>
 */
@Mapper
public interface AiModelIconMapper {

    int insert(AiModelIcon icon);

    List<AiModelIcon> findPage();

    AiModelIcon findById(@Param("id") long id);

    AiModelIcon findByIdForShare(@Param("id") long id);

    AiModelIcon findByIdForUpdate(@Param("id") long id);

    int update(AiModelIcon icon);

    int deleteById(@Param("id") long id);

    int countModelReferences(@Param("iconId") long iconId);

    boolean existsEnabledReference(@Param("iconId") long iconId);
}
