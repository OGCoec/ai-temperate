package com.example.temperate.service.user.aiconversation.image;

import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import java.util.List;

/**
 * 负责按模型规范名称、产品档位和画幅解析不可绕过的图片生成参数。
 */
public interface AiConversationImageProfileService {

    AiConversationImageProfile required(
            AiModelProvider provider,
            String modelName,
            AiConversationReasoningEffort productTier,
            AiConversationImageAspect aspect);

    List<Short> supportedLevels(AiModelProvider provider, String modelName);

    List<AiConversationImageAspect> supportedAspects(
            AiModelProvider provider,
            String modelName);

    boolean supports(AiModelProvider provider, String modelName);

    default AiConversationImageProfile required(
            String modelName,
            AiConversationReasoningEffort productTier,
            AiConversationImageAspect aspect) {
        return required(AiModelProvider.OPENAI, modelName, productTier, aspect);
    }

    default List<Short> supportedLevels(String modelName) {
        return supportedLevels(AiModelProvider.OPENAI, modelName);
    }

    default List<AiConversationImageAspect> supportedAspects(String modelName) {
        return supportedAspects(AiModelProvider.OPENAI, modelName);
    }

    default boolean supports(String modelName) {
        return supports(AiModelProvider.OPENAI, modelName);
    }
}
