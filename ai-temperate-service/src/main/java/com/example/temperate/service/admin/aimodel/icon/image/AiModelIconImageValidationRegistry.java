package com.example.temperate.service.admin.aimodel.icon.image;

import com.example.temperate.service.admin.aimodel.icon.image.strategy.AiModelIconImageValidationStrategy;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 在应用启动时收集全部模型图标格式策略，并按稳定格式枚举提供不可变查找。
 *
 * <p>重复格式或缺少规定格式意味着安全策略集合不完整，必须直接阻止应用启动，不能在
 * 请求到来后静默降级成只检查魔数。</p>
 */
@Component
public final class AiModelIconImageValidationRegistry {

    private final Map<AiModelIconImageFormat, AiModelIconImageValidationStrategy> strategies;

    public AiModelIconImageValidationRegistry(
            Map<String, AiModelIconImageValidationStrategy> strategyBeans) {
        EnumMap<AiModelIconImageFormat, AiModelIconImageValidationStrategy> registered =
                new EnumMap<>(AiModelIconImageFormat.class);
        for (AiModelIconImageValidationStrategy strategy :
                Objects.requireNonNull(strategyBeans).values()) {
            AiModelIconImageValidationStrategy previous =
                    registered.put(strategy.type(), strategy);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate AI model icon image strategy: " + strategy.type());
            }
        }
        for (AiModelIconImageFormat format : AiModelIconImageFormat.values()) {
            if (!registered.containsKey(format)) {
                throw new IllegalStateException(
                        "Missing AI model icon image strategy: " + format);
            }
        }
        this.strategies = Map.copyOf(registered);
    }

    public AiModelIconImageValidationStrategy getRequired(AiModelIconImageFormat format) {
        AiModelIconImageValidationStrategy strategy = strategies.get(format);
        if (strategy == null) {
            throw new IllegalStateException(
                    "Missing AI model icon image strategy: " + format);
        }
        return strategy;
    }
}
