package com.example.temperate.service.admin.aimodel.icon.image.strategy.impl;

import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageFormat;
import org.springframework.stereotype.Component;

/**
 * 完整解码并验证单帧 JPEG/JPG 模型图标。
 */
@Component
public final class JpegAiModelIconImageValidationStrategy
        extends AbstractImageIoAiModelIconImageValidationStrategy {

    public JpegAiModelIconImageValidationStrategy() {
        super(AiModelIconImageFormat.JPEG, 1, true, "JPEG", "JPG");
    }
}
