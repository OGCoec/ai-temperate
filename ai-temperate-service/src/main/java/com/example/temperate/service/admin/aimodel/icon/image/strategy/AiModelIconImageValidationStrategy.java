package com.example.temperate.service.admin.aimodel.icon.image.strategy;

import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageFormat;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageMetadata;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageValidationContext;

/**
 * 定义单一模型图标格式的完整内容验证边界。
 *
 * <p>实现必须返回稳定格式枚举，并完成格式特有的解码、尺寸、帧数和安全检查；不得信任
 * 文件名、URL 后缀或仅依赖魔数。</p>
 */
public interface AiModelIconImageValidationStrategy {

    AiModelIconImageFormat type();

    default AiModelIconImageMetadata validate(
            byte[] bytes,
            String declaredContentType) {
        return validate(
                bytes,
                declaredContentType,
                AiModelIconImageValidationContext.strict());
    }

    AiModelIconImageMetadata validate(
            byte[] bytes,
            String declaredContentType,
            AiModelIconImageValidationContext context);
}
