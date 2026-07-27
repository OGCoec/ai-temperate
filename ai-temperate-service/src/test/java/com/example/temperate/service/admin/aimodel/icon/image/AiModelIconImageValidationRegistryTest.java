package com.example.temperate.service.admin.aimodel.icon.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.aimodel.icon.image.strategy.AiModelIconImageValidationStrategy;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证模型图标格式策略在启动阶段完整注册，并拒绝重复格式或缺少规定格式。
 */
final class AiModelIconImageValidationRegistryTest {

    @Test
    void registersEveryRequiredFormatAndSelectsByStableEnum() {
        Map<String, AiModelIconImageValidationStrategy> strategies = allStrategies();
        AiModelIconImageValidationRegistry registry =
                new AiModelIconImageValidationRegistry(strategies);

        for (AiModelIconImageFormat format : AiModelIconImageFormat.values()) {
            assertThat(registry.getRequired(format).type()).isEqualTo(format);
        }
    }

    @Test
    void rejectsDuplicateStableFormatAtStartup() {
        Map<String, AiModelIconImageValidationStrategy> strategies = allStrategies();
        strategies.put("duplicatePng", stub(AiModelIconImageFormat.PNG));

        assertThatThrownBy(() -> new AiModelIconImageValidationRegistry(strategies))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PNG");
    }

    @Test
    void rejectsMissingRequiredFormatAtStartup() {
        Map<String, AiModelIconImageValidationStrategy> strategies = allStrategies();
        strategies.remove(AiModelIconImageFormat.AVIF.name());

        assertThatThrownBy(() -> new AiModelIconImageValidationRegistry(strategies))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AVIF");
    }

    private static Map<String, AiModelIconImageValidationStrategy> allStrategies() {
        Map<String, AiModelIconImageValidationStrategy> strategies = new LinkedHashMap<>();
        for (AiModelIconImageFormat format : AiModelIconImageFormat.values()) {
            strategies.put(format.name(), stub(format));
        }
        return strategies;
    }

    private static AiModelIconImageValidationStrategy stub(AiModelIconImageFormat format) {
        return new AiModelIconImageValidationStrategy() {
            @Override
            public AiModelIconImageFormat type() {
                return format;
            }

            @Override
            public AiModelIconImageMetadata validate(
                    byte[] bytes,
                    String declaredContentType,
                    AiModelIconImageValidationContext context) {
                return new AiModelIconImageMetadata(format, 1, 1, 1, bytes);
            }
        };
    }
}
