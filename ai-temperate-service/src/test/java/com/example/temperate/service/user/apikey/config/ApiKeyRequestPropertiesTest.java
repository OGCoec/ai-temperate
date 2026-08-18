package com.example.temperate.service.user.apikey.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来锁定公开 Chat 请求、单个工具描述和完整工具集合之间的分层字节预算，防止配置组合形成相互矛盾的安全边界。
 */
final class ApiKeyRequestPropertiesTest {

    @Test
    void defaultsProvideLayeredClaudeCodeToolBudgets() {
        ApiKeyProperties.Request request = new ApiKeyProperties().getRequest();

        assertThat(request.getMaxToolDescriptionBytes()).isEqualTo(32_768);
        assertThat(request.getMaxToolDefinitionsBytes()).isEqualTo(524_288);
        assertThat(request.getMaxToolDescriptionBytes())
                .isLessThanOrEqualTo(request.getMaxToolDefinitionsBytes());
        assertThat(request.getMaxToolDefinitionsBytes())
                .isLessThanOrEqualTo(request.getMaxBodyBytes());
        assertThat(request.isToolBudgetsValid()).isTrue();
    }

    @Test
    void rejectsDescriptionAggregateAndBodyBudgetInversions() {
        ApiKeyProperties.Request request = new ApiKeyProperties.Request();
        request.setMaxToolDescriptionBytes(262_144);
        request.setMaxToolDefinitionsBytes(131_072);
        assertThat(request.isToolBudgetsValid()).isFalse();

        request = new ApiKeyProperties.Request();
        request.setMaxBodyBytes(262_144);
        request.setMaxToolDefinitionsBytes(524_288);
        assertThat(request.isToolBudgetsValid()).isFalse();
    }

    @Test
    void enablesLooseCompatibilityByDefaultAndSanitizesPassthroughModels() {
        ApiKeyProperties.OpenAiCompatibility compatibility =
                new ApiKeyProperties().getOpenAiCompatibility();

        compatibility.setPassthroughModels(List.of(
                " GPT-TEST ", "gpt-test", "", "claude-test"));

        assertThat(compatibility.isEnabled()).isTrue();
        assertThat(compatibility.getPassthroughModels())
                .containsExactly("GPT-TEST", "gpt-test", "claude-test");
    }
}
