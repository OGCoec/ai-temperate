package com.example.temperate.service.admin.aimodel.icon.image.strategy.impl;

import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageFormat;
import org.springframework.stereotype.Component;

/**
 * 使用 TwelveMonkeys ImageIO Reader 全条目解码并验证最多二十个尺寸的 ICO 模型图标。
 */
@Component
public final class IcoAiModelIconImageValidationStrategy
        extends AbstractImageIoAiModelIconImageValidationStrategy {

    public static final int MAX_ENTRIES = 20;

    public IcoAiModelIconImageValidationStrategy() {
        super(AiModelIconImageFormat.ICO, MAX_ENTRIES, false, "ICO");
    }
}
