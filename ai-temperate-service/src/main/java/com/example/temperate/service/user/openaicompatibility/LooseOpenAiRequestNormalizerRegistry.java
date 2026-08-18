package com.example.temperate.service.user.openaicompatibility;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 该 Registry 是来在启动时汇总全部宽松规范化策略并拒绝重复协议，业务调用只依赖稳定协议枚举。
 */
@Component
public final class LooseOpenAiRequestNormalizerRegistry {

    private final Map<OpenAiCompatibilityProtocol, LooseOpenAiRequestNormalizer> normalizers;

    public LooseOpenAiRequestNormalizerRegistry(
            Map<String, LooseOpenAiRequestNormalizer> normalizerBeans) {
        Objects.requireNonNull(normalizerBeans);
        EnumMap<OpenAiCompatibilityProtocol, LooseOpenAiRequestNormalizer> registered =
                new EnumMap<>(OpenAiCompatibilityProtocol.class);
        for (LooseOpenAiRequestNormalizer normalizer : normalizerBeans.values()) {
            LooseOpenAiRequestNormalizer previous = registered.put(
                    normalizer.protocol(), normalizer);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate OpenAI compatibility normalizer: " + normalizer.protocol());
            }
        }
        this.normalizers = Map.copyOf(registered);
    }

    public LooseOpenAiRequestNormalizer getRequired(OpenAiCompatibilityProtocol protocol) {
        LooseOpenAiRequestNormalizer normalizer = normalizers.get(
                Objects.requireNonNull(protocol));
        if (normalizer == null) {
            throw new IllegalArgumentException(
                    "Unsupported OpenAI compatibility protocol: " + protocol);
        }
        return normalizer;
    }
}
