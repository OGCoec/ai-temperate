package com.example.temperate.service.user.apichat.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apichat.ValidatedApiChatRequest;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来约束四厂商 Registry 的完整选择、重复类型启动失败和未知厂商受控失败，不允许通过 Bean 名或 if/switch 分发。
 */
final class ApiChatProviderAdapterRegistryTest {

    @Test
    void selectsEveryRequiredProviderByStableVendor() {
        Map<String, ApiChatProviderAdapter> adapters = new LinkedHashMap<>();
        for (AiModelProvider provider : AiModelProvider.values()) {
            adapters.put(provider.vendor(), adapter(provider));
        }
        ApiChatProviderAdapterRegistry registry =
                new ApiChatProviderAdapterRegistry(adapters);

        for (AiModelProvider provider : AiModelProvider.values()) {
            assertThat(registry.getRequired(provider.vendor()).type()).isEqualTo(provider);
        }
    }

    @Test
    void duplicateProviderFailsAtConstruction() {
        assertThatThrownBy(() -> new ApiChatProviderAdapterRegistry(Map.of(
                "one", adapter(AiModelProvider.OPENAI),
                "two", adapter(AiModelProvider.OPENAI))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate API chat provider adapter");
    }

    @Test
    void unknownProviderReturnsControlledModelError() {
        ApiChatProviderAdapterRegistry registry =
                new ApiChatProviderAdapterRegistry(Map.of());

        assertThatThrownBy(() -> registry.getRequired("unknown"))
                .isInstanceOf(ApiChatException.class)
                .extracting(failure -> ((ApiChatException) failure).code())
                .isEqualTo(ApiChatErrorCode.MODEL_NOT_FOUND);
    }

    private static ApiChatProviderAdapter adapter(AiModelProvider provider) {
        return new ApiChatProviderAdapter() {
            @Override
            public AiModelProvider type() {
                return provider;
            }

            @Override
            public ObjectNode adapt(ValidatedApiChatRequest request) {
                throw new UnsupportedOperationException("selection test only");
            }
        };
    }
}
