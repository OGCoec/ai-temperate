package com.example.temperate.service.user.aiconversation.model.stream.xai.video;

import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMode;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 在启动时把全部 xAI 视频模式 Bean 转换为不可变枚举映射，并拒绝重复或未知选择。
 */
@Component
public final class XaiVideoOperationStrategyRegistry {

    private final Map<AiConversationVideoMode, XaiVideoOperationStrategy> strategies;

    public XaiVideoOperationStrategyRegistry(
            Map<String, XaiVideoOperationStrategy> strategyBeans) {
        EnumMap<AiConversationVideoMode, XaiVideoOperationStrategy> registered =
                new EnumMap<>(AiConversationVideoMode.class);
        for (XaiVideoOperationStrategy strategy
                : Objects.requireNonNull(strategyBeans).values()) {
            XaiVideoOperationStrategy previous = registered.put(
                    Objects.requireNonNull(strategy.mode()), strategy);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate xAI video operation strategy: "
                                + strategy.mode());
            }
        }
        for (AiConversationVideoMode mode : AiConversationVideoMode.values()) {
            if (!registered.containsKey(mode)) {
                throw new IllegalStateException(
                        "Missing xAI video operation strategy: " + mode);
            }
        }
        strategies = Map.copyOf(registered);
    }

    public XaiVideoOperationStrategy getRequired(AiConversationVideoMode mode) {
        XaiVideoOperationStrategy strategy = mode == null
                ? null
                : strategies.get(mode);
        if (strategy == null) {
            throw new IllegalArgumentException(
                    "Unsupported xAI video operation: " + mode);
        }
        return strategy;
    }
}
