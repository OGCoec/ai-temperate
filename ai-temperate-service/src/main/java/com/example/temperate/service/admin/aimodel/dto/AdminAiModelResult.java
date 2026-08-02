package com.example.temperate.service.admin.aimodel.dto;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 返回管理员可查看的 AI 模型字段、原始 Token 与 K 展示值、状态和能力集合。
 *
 * <p>内部资源 ID 只在 Service 中转换为固定 11 位 Base64URL；Token Long 由 HTTP 专用
 * 序列化器输出为十进制字符串，K 保持 JSON 整数。名称与描述命中词只来自当前列表搜索上下文，
 * 并且分别对应两个 JSONB GIN 检索字段；其他响应固定返回空集合。</p>
 */
public record AdminAiModelResult(
        String publicId,
        String modelName,
        List<String> modelNameMatchedTokens,
        String description,
        List<String> descriptionMatchedTokens,
        String iconPublicId,
        String icon,
        List<String> tags,
        String vendor,
        BigDecimal inputRatio,
        BigDecimal cachedInputRatio,
        BigDecimal outputRatio,
        Long contextWindowTokens,
        Integer contextWindowK,
        Long maxOutputTokens,
        Integer maxOutputK,
        boolean enabled,
        List<AiModelCapabilityCode> capabilities,
        LocalDate createdAt,
        LocalDate updatedAt) {

    public AdminAiModelResult {
        modelNameMatchedTokens = modelNameMatchedTokens == null
                ? List.of()
                : List.copyOf(modelNameMatchedTokens);
        descriptionMatchedTokens = descriptionMatchedTokens == null
                ? List.of()
                : List.copyOf(descriptionMatchedTokens);
        tags = tags == null ? List.of() : List.copyOf(tags);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
