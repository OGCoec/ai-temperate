package com.example.temperate.service.admin.aimodel.cache;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import java.math.BigDecimal;
import java.util.List;

/**
 * 表示加密快照内单个已启用 AI 模型的不可变缓存视图。
 *
 * <p>内部 Snowflake ID 只存在于加密明文中，Redis Key 不携带模型名称、厂商或标识。</p>
 */
public record AiModelCacheEntry(
        long id,
        String modelName,
        String vendor,
        String description,
        String icon,
        List<String> tags,
        BigDecimal inputRatio,
        BigDecimal cachedInputRatio,
        BigDecimal outputRatio,
        List<AiModelCapabilityCode> capabilities) {

    public AiModelCacheEntry {
        tags = tags == null ? List.of() : List.copyOf(tags);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
