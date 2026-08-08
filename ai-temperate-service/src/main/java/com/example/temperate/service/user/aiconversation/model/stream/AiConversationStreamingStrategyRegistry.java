package com.example.temperate.service.user.aiconversation.model.stream;

import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 在启动时收集并校验全部模型流式策略，以稳定协议枚举而不是客户端输入选择实现。
 */
@Component
public final class AiConversationStreamingStrategyRegistry {

    private static final Map<AiModelProvider,
            Set<AiConversationStreamingProtocol>> REQUIRED_STRATEGIES =
            requiredStrategies();

    private final Map<AiModelProvider,
            Map<AiConversationStreamingProtocol, AiConversationStreamingStrategy>> strategies;

    public AiConversationStreamingStrategyRegistry(
            Map<String, AiConversationStreamingStrategy> strategyBeans) {
        EnumMap<AiModelProvider,
                EnumMap<AiConversationStreamingProtocol,
                        AiConversationStreamingStrategy>> registered =
                new EnumMap<>(AiModelProvider.class);
        for (AiConversationStreamingStrategy strategy : strategyBeans.values()) {
            Set<AiConversationStreamingProtocol> supported =
                    REQUIRED_STRATEGIES.get(strategy.provider());
            if (supported == null || !supported.contains(strategy.protocol())) {
                throw new IllegalStateException(
                        "Unsupported AI conversation streaming strategy: "
                                + strategy.provider() + "/" + strategy.protocol());
            }
            EnumMap<AiConversationStreamingProtocol, AiConversationStreamingStrategy>
                    providerStrategies = registered.computeIfAbsent(
                            strategy.provider(),
                            ignored -> new EnumMap<>(
                                    AiConversationStreamingProtocol.class));
            AiConversationStreamingStrategy previous = providerStrategies.put(
                    strategy.protocol(), strategy);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate AI conversation streaming strategy: "
                                + strategy.provider() + "/" + strategy.protocol());
            }
        }
        EnumMap<AiModelProvider,
                Map<AiConversationStreamingProtocol,
                        AiConversationStreamingStrategy>> immutable =
                new EnumMap<>(AiModelProvider.class);
        for (Map.Entry<AiModelProvider, Set<AiConversationStreamingProtocol>> entry
                : REQUIRED_STRATEGIES.entrySet()) {
            AiModelProvider provider = entry.getKey();
            EnumMap<AiConversationStreamingProtocol, AiConversationStreamingStrategy>
                    providerStrategies = registered.get(provider);
            for (AiConversationStreamingProtocol protocol : entry.getValue()) {
                if (providerStrategies == null
                        || !providerStrategies.containsKey(protocol)) {
                    throw new IllegalStateException(
                            "Missing AI conversation streaming strategy: "
                                    + provider + "/" + protocol);
                }
            }
            immutable.put(provider, Map.copyOf(providerStrategies));
        }
        strategies = Map.copyOf(immutable);
    }

    public AiConversationStreamingStrategy getRequired(
            AiModelProvider provider,
            AiConversationStreamingProtocol protocol) {
        Map<AiConversationStreamingProtocol, AiConversationStreamingStrategy>
                providerStrategies = strategies.get(provider);
        AiConversationStreamingStrategy strategy = providerStrategies == null
                ? null
                : providerStrategies.get(protocol);
        if (strategy == null) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_MODEL_NOT_AVAILABLE,
                    "所选模型供应商不支持该会话协议",
                    false);
        }
        return strategy;
    }

    private static Map<AiModelProvider, Set<AiConversationStreamingProtocol>>
            requiredStrategies() {
        EnumMap<AiModelProvider, Set<AiConversationStreamingProtocol>> matrix =
                new EnumMap<>(AiModelProvider.class);
        matrix.put(AiModelProvider.OPENAI, Set.copyOf(EnumSet.of(
                AiConversationStreamingProtocol.CHAT_COMPLETIONS,
                AiConversationStreamingProtocol.RESPONSES_WEB_SEARCH,
                AiConversationStreamingProtocol.IMAGES_GENERATION)));
        matrix.put(AiModelProvider.XAI, Set.copyOf(EnumSet.of(
                AiConversationStreamingProtocol.CHAT_COMPLETIONS,
                AiConversationStreamingProtocol.RESPONSES_WEB_SEARCH,
                AiConversationStreamingProtocol.IMAGES_GENERATION,
                AiConversationStreamingProtocol.VIDEOS_GENERATION)));
        matrix.put(AiModelProvider.ANTHROPIC, Set.copyOf(EnumSet.of(
                AiConversationStreamingProtocol.CHAT_COMPLETIONS,
                AiConversationStreamingProtocol.RESPONSES_WEB_SEARCH)));
        matrix.put(AiModelProvider.GOOGLE, Set.copyOf(EnumSet.of(
                AiConversationStreamingProtocol.CHAT_COMPLETIONS,
                AiConversationStreamingProtocol.RESPONSES_WEB_SEARCH,
                AiConversationStreamingProtocol.IMAGES_GENERATION)));
        // 支持矩阵是启动期唯一真相，禁止通过 Bean 是否存在静默扩大供应商能力。
        return Map.copyOf(matrix);
    }
}
