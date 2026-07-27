package com.example.temperate.service.admin.aimodel.icon.image;

import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 统一编排模型图标真实格式检测和格式专用完整验证。
 *
 * <p>该门面先依据内容签名选择稳定策略，再由策略核对 Content-Type、完整解码和格式特有
 * 安全边界；本地上传只能使用验证结果中的格式和存储字节写入 OSS，外链验证只保留最终 URL。</p>
 */
@Component
public final class AiModelIconImageValidator {

    public static final int MAX_DIMENSION = 4096;
    public static final long MAX_TOTAL_PIXELS =
            (long) MAX_DIMENSION * MAX_DIMENSION;

    private final AiModelIconImageFormatDetector formatDetector;
    private final AiModelIconImageValidationRegistry strategyRegistry;

    public AiModelIconImageValidator(
            AiModelIconImageFormatDetector formatDetector,
            AiModelIconImageValidationRegistry strategyRegistry) {
        this.formatDetector = Objects.requireNonNull(formatDetector);
        this.strategyRegistry = Objects.requireNonNull(strategyRegistry);
    }

    public AiModelIconImageMetadata validate(
            byte[] bytes,
            String declaredContentType) {
        return validate(
                bytes,
                declaredContentType,
                AiModelIconImageValidationContext.strict());
    }

    /**
     * 在调用方已经由服务端可信来源 Registry 解析上下文时执行格式验证。
     *
     * <p>本地上传和普通外链不得自行构造可信档位，必须继续使用无上下文重载。</p>
     */
    public AiModelIconImageMetadata validate(
            byte[] bytes,
            String declaredContentType,
            AiModelIconImageValidationContext context) {
        AiModelIconImageFormat format = formatDetector.detect(bytes);
        return strategyRegistry.getRequired(format).validate(
                bytes,
                declaredContentType,
                Objects.requireNonNull(context));
    }
}
