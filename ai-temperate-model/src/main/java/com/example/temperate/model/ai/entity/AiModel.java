package com.example.temperate.model.ai.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 表示 AI 模型目录、计费倍率与生成容量在 PostgreSQL 中的持久化实体。
 *
 * <p>标签以 JSON 文本停留在持久化边界，Token 限制始终保存原始整数；该实体不负责
 * 公共 ID、K 单位换算、能力编排或缓存加密。</p>
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
    private BigDecimal cachedInputRatio;
    private BigDecimal outputRatio;
    private Long contextWindowTokens;
    private Long maxOutputTokens;
    private Boolean enabled;
    private Long rowVersion;
    private LocalDate createdAt;
    private LocalDate updatedAt;
}
