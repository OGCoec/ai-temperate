package com.example.temperate.model.ai.entity;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 表示与一个 AI 模型逻辑关联的单条能力持久化记录。
 *
 * <p>关联完整性由写入事务和孤儿检查 SQL 补偿，不依赖数据库物理外键。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class AiModelCapability {

    private Long id;
    private Long aiModelId;
    private AiModelCapabilityCode capabilityCode;
    private LocalDate createdAt;
    private LocalDate updatedAt;
}
