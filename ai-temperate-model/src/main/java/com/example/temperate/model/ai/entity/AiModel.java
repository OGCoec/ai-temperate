package com.example.temperate.model.ai.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 表示 AI 模型目录在 PostgreSQL 中的持久化实体。
 *
 * <p>标签以 JSON 文本停留在持久化边界，由 Service 转换为稳定集合；该实体不负责公共 ID、能力编排或缓存加密。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class AiModel {

    private Long id;
    private String modelName;
    private String description;
    private Long iconId;
    private String icon;
    private String tagsJson;
    private String modelNameTokensJson;
    private String descriptionTokensJson;
    private String vendor;
    private BigDecimal inputRatio;
    private BigDecimal outputRatio;
    private Boolean enabled;
    private Long rowVersion;
    private LocalDate createdAt;
    private LocalDate updatedAt;
}
