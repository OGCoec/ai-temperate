package com.example.temperate.service.user.aiconversation.model.stream;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 在启动时收集并校验全部模型流式策略，以稳定协议枚举而不是客户端输入选择实现。
 */
@Component
public final class AiConversationStreamingStrategyRegistry {

    private final Map<AiConversationStreamingProtocol, AiConversationStreamingStrategy>
            strategies;

    public AiConversationStreamingStrategyRegistry(
            Map<String, AiConversationStreamingStrategy> strategyBeans) {
        EnumMap<AiConversationStreamingProtocol, AiConversationStreamingStrategy>
                registered = new EnumMap<>(AiConversationStreamingProtocol.class);
        for (AiConversationStreamingStrategy strategy : strategyBeans.values()) {
            AiConversationStreamingStrategy previous = registered.put(
                    strategy.protocol(), strategy);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate AI conversation streaming strategy: "
                                + strategy.protocol());
            }
        }
        for (AiConversationStreamingProtocol protocol
                : AiConversationStreamingProtocol.values()) {
            if (!registered.containsKey(protocol)) {
                throw new IllegalStateException(
                        "Missing AI conversation streaming strategy: " + protocol);
            }
        }
        strategies = Map.copyOf(registered);
    }

    public AiConversationStreamingStrategy required(
            AiConversationStreamingProtocol protocol) {
        return Objects.requireNonNull(strategies.get(protocol));
    }
}
