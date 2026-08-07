package com.example.temperate.service.user.aimodel.dto;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import java.math.BigDecimal;
import java.util.List;

/**
 * 返回普通用户可查看的已启用 AI 模型公共信息、本地计费倍率、推理强度和图片生成档位能力。
 *
 * <p>倍率是本项目的计费配置，不表示厂商官方美元价格；缓存输入倍率对应厂商 usage 中的
 * cached_tokens，与 Redis 无关。名称和描述命中词只用于当前搜索结果的安全文本高亮，详情和普通浏览为空。</p>
 */
public record UserAiModelResult(
        String publicId,
        String modelName,
        List<String> modelNameMatchedTokens,
        String vendor,
        String description,
        List<String> descriptionMatchedTokens,
        String icon,
        List<String> tags,
        BigDecimal inputRatio,
        BigDecimal cachedInputRatio,
        BigDecimal outputRatio,
        long contextWindowTokens,
        long contextWindowK,
        long maxOutputTokens,
        long maxOutputK,
        List<AiModelCapabilityCode> capabilities,
        List<Short> supportedReasoningEffortLevels,
        short defaultReasoningEffortLevel,
        List<Short> supportedImageGenerationLevels,
        List<AiConversationImageAspect> supportedImageAspects) {

    public UserAiModelResult {
        if (contextWindowTokens <= 0L
                || contextWindowK <= 0L
                || maxOutputTokens <= 0L
                || maxOutputK <= 0L) {
            throw new IllegalArgumentException(
                    "AI model context and output limits must be positive.");
        }
        modelNameMatchedTokens = modelNameMatchedTokens == null
                ? List.of()
                : List.copyOf(modelNameMatchedTokens);
        descriptionMatchedTokens = descriptionMatchedTokens == null
                ? List.of()
                : List.copyOf(descriptionMatchedTokens);
        tags = tags == null ? List.of() : List.copyOf(tags);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        supportedReasoningEffortLevels =
                supportedReasoningEffortLevels == null
                        ? List.of()
                        : List.copyOf(supportedReasoningEffortLevels);
        if (!supportedReasoningEffortLevels.contains(
                defaultReasoningEffortLevel)) {
            throw new IllegalArgumentException(
                    "Default reasoning effort must be supported.");
        }
        supportedImageGenerationLevels = supportedImageGenerationLevels == null
                ? List.of() : List.copyOf(supportedImageGenerationLevels);
        supportedImageAspects = supportedImageAspects == null
                ? List.of() : List.copyOf(supportedImageAspects);
    }
}
