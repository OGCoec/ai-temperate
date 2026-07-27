package com.example.temperate.service.admin.aimodel.icon.image.strategy.impl;

import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageFormat;
import org.springframework.stereotype.Component;

/**
 * 使用 AVIF 原生 ImageIO Reader 完整解码并验证静态 AVIF 模型图标。
 *
 * <p>第一阶段明确拒绝多帧 AVIF；需要动画时只能使用受限 GIF。</p>
 */
@Component
public final class AvifAiModelIconImageValidationStrategy
        extends AbstractImageIoAiModelIconImageValidationStrategy {

    public AvifAiModelIconImageValidationStrategy() {
        super(AiModelIconImageFormat.AVIF, 1, true, "AVIF");
    }
}
