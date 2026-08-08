package com.example.temperate.service.user.aiconversation.video;

import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import java.util.List;

/**
 * 负责按供应商、模型、模式和清晰度解析不可绕过的视频能力及官方价格配置。
 */
public interface AiConversationVideoProfileService {

    AiConversationVideoModelProfile required(
            AiModelProvider provider,
            String modelName,
            AiConversationVideoMode mode,
            AiConversationVideoResolution resolution);

    boolean supports(AiModelProvider provider, String modelName);

    List<AiConversationVideoMode> supportedModes(
            AiModelProvider provider,
            String modelName);

    List<AiConversationVideoResolution> supportedResolutions(
            AiModelProvider provider,
            String modelName,
            AiConversationVideoMode mode);
}
