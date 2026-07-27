package com.example.temperate.service.admin.aimodel.dto;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 返回管理员可查看的 AI 模型字段、公共 ID、状态和能力集合。
 *
 * <p>内部 BIGINT 只在 Service 中转换为固定 11 位 Base64URL，不直接暴露给 HTTP 客户端。</p>
 */
public record AdminAiModelResult(
        String publicId,
        String modelName,
        String description,
        String iconPublicId,
        String icon,
        List<String> tags,
        String vendor,
        BigDecimal inputRatio,
        BigDecimal outputRatio,
        boolean enabled,
        List<AiModelCapabilityCode> capabilities,
        LocalDate createdAt,
        LocalDate updatedAt) {

    public AdminAiModelResult {
        tags = tags == null ? List.of() : List.copyOf(tags);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
