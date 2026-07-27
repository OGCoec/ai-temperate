package com.example.temperate.service.admin.aimodel.dto;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 返回管理员单模型详情、当前能力和可选能力全集。
 *
 * <p>数据库 ID 不向外暴露，{@code rowVersion} 与 HTTP ETag 保持同一乐观锁版本。</p>
 */
public record AdminAiModelDetailResult(
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
        List<AiModelCapabilityCode> availableCapabilities,
        long rowVersion,
        LocalDate createdAt,
        LocalDate updatedAt) {
}
