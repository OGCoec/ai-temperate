package com.example.temperate.service.user.apichat.provider;

import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 该 Registry 是来把 Spring 注入的全部厂商适配器转换为不可变 EnumMap，并在启动时拒绝重复厂商实现。
 */
@Component
public final class ApiChatProviderAdapterRegistry {

    private final Map<AiModelProvider, ApiChatProviderAdapter> adapters;

    public ApiChatProviderAdapterRegistry(Map<String, ApiChatProviderAdapter> beans) {
        EnumMap<AiModelProvider, ApiChatProviderAdapter> registered =
                new EnumMap<>(AiModelProvider.class);
        for (ApiChatProviderAdapter adapter : beans.values()) {
            ApiChatProviderAdapter previous = registered.put(adapter.type(), adapter);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate API chat provider adapter: " + adapter.type());
            }
        }
        this.adapters = Map.copyOf(registered);
    }

    public ApiChatProviderAdapter getRequired(String vendor) {
        AiModelProvider provider = null;
        if (vendor != null) {
            for (AiModelProvider candidate : AiModelProvider.values()) {
                if (candidate.vendor().equalsIgnoreCase(vendor.trim())) {
                    provider = candidate;
                    break;
                }
            }
        }
        ApiChatProviderAdapter adapter = provider == null ? null : adapters.get(provider);
        if (adapter == null) {
            throw new ApiChatException(
                    ApiChatErrorCode.MODEL_NOT_FOUND,
                    "The model provider is unsupported.",
                    "model");
        }
        return adapter;
    }
}
