package com.example.temperate.model.ai.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 该只读投影是来向 API Key 管理和认证缓存提供当前有效模型授权及模型可用状态，不暴露数据库主键到 HTTP 边界。
 */
@Getter
@Setter
@NoArgsConstructor
public class ApiKeyModelGrantView {

    private Long aiModelId;
    private String modelName;
    private String vendor;
    private Boolean enabled;
}
