package com.example.temperate.service.user.aiconversation.image;

import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import java.util.List;

/**
 * 负责按模型规范名称、产品档位和画幅解析不可绕过的图片生成参数。
 */
public interface AiConversationImageProfileService {

    AiConversationImageProfile required(
            String modelName,
            AiConversationReasoningEffort productTier,
            AiConversationImageAspect aspect);

    List<Short> supportedLevels(String modelName);

    List<AiConversationImageAspect> supportedAspects(String modelName);

    boolean supports(String modelName);
}
