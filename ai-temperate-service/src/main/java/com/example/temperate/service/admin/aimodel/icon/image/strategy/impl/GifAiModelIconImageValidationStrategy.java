package com.example.temperate.service.admin.aimodel.icon.image.strategy.impl;

import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageFormat;
import org.springframework.stereotype.Component;

/**
 * 使用 JDK ImageIO Reader 全帧解码并验证最多一百二十帧的 GIF 模型图标。
 */
@Component
public final class GifAiModelIconImageValidationStrategy
        extends AbstractImageIoAiModelIconImageValidationStrategy {

    public static final int MAX_FRAMES = 120;

    public GifAiModelIconImageValidationStrategy() {
        super(AiModelIconImageFormat.GIF, MAX_FRAMES, false, "GIF");
    }
}
