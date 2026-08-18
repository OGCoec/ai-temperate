package com.example.temperate.service.user.apiresponse.provider;

import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 该 Registry 是来把全部 Responses 厂商策略转换为不可变 EnumMap，并在启动时拒绝重复注册或运行时未知厂商。
 */
@Component
public final class ApiResponseProviderAdapterRegistry {

    private final Map<AiModelProvider, ApiResponseProviderAdapter> adapters;

    public ApiResponseProviderAdapterRegistry(Map<String, ApiResponseProviderAdapter> beans) {
        EnumMap<AiModelProvider, ApiResponseProviderAdapter> registered =
                new EnumMap<>(AiModelProvider.class);
        for (ApiResponseProviderAdapter adapter : beans.values()) {
            ApiResponseProviderAdapter previous = registered.put(adapter.type(), adapter);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate API response provider adapter: " + adapter.type());
            }
        }
        this.adapters = Map.copyOf(registered);
    }

    public ApiResponseProviderAdapter getRequired(String vendor) {
        AiModelProvider provider = null;
        if (vendor != null) {
            for (AiModelProvider candidate : AiModelProvider.values()) {
                if (candidate.vendor().equalsIgnoreCase(vendor.trim())) {
                    provider = candidate;
                    break;
                }
            }
        }
        ApiResponseProviderAdapter adapter = provider == null ? null : adapters.get(provider);
        if (adapter == null) {
            throw new ApiChatException(
                    ApiChatErrorCode.MODEL_NOT_FOUND,
                    "The model provider is unsupported.",
                    "model");
        }
        return adapter;
    }
}
