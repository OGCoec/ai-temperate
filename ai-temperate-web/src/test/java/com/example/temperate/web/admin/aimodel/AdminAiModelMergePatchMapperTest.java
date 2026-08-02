package com.example.temperate.web.admin.aimodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.aimodel.exception.AdminAiModelErrorCode;
import com.example.temperate.service.admin.aimodel.exception.AdminAiModelException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * 验证 JSON Merge Patch 只接受白名单字段，并保留字段省略与显式清空的差异。
 */
final class AdminAiModelMergePatchMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AdminAiModelMergePatchMapper mapper =
            new AdminAiModelMergePatchMapper(objectMapper);

    @Test
    void distinguishesOmittedFieldsFromExplicitNull() throws Exception {
        var command = mapper.parse(objectMapper.readTree("""
                {
                  "description": null,
                  "iconPublicId": null,
                  "inputRatio": 1.25,
                  "cachedInputRatio": 0.125,
                  "contextWindowK": 256,
                  "maxOutputK": 32,
                  "capabilities": ["RESPONSES", "IMAGE"]
                }
                """));

        assertThat(command.modelName().present()).isFalse();
        assertThat(command.description().present()).isTrue();
        assertThat(command.description().value()).isNull();
        assertThat(command.iconPublicId().present()).isTrue();
        assertThat(command.iconPublicId().value()).isNull();
        assertThat(command.inputRatio().value()).isEqualByComparingTo(new BigDecimal("1.25"));
        assertThat(command.cachedInputRatio().value())
                .isEqualByComparingTo(new BigDecimal("0.125"));
        assertThat(command.contextWindowTokens().value()).isEqualTo(256000L);
        assertThat(command.maxOutputTokens().value()).isEqualTo(32000L);
        assertThat(command.capabilities().value()).containsExactly("RESPONSES", "IMAGE");
    }

    @Test
    void rejectsEmptyUnknownAndImmutableFields() throws Exception {
        assertPatchInvalid("{}");
        assertPatchInvalid("{\"unknown\":true}");
        assertPatchInvalid("{\"enabled\":true}");
        assertPatchInvalid("{\"rowVersion\":9}");
        assertPatchInvalid("{\"icon\":\"https://example.test/legacy.png\"}");
    }

    @Test
    void rejectsWrongJsonTypes() throws Exception {
        assertPatchInvalid("{\"tags\":\"chat\"}");
        assertPatchInvalid("{\"inputRatio\":\"1.25\"}");
        assertPatchInvalid("{\"cachedInputRatio\":null}");
        assertPatchInvalid("{\"capabilities\":[\"RESPONSES\",9]}");
        assertPatchInvalid("{\"contextWindowK\":null}");
        assertPatchInvalid("{\"contextWindowK\":\"256\"}");
        assertPatchInvalid("{\"contextWindowK\":256.0}");
        assertPatchInvalid("{\"contextWindowK\":2.56e2}");
        assertPatchInvalid("{\"maxOutputK\":true}");
        assertPatchInvalid("{\"contextWindowTokens\":256000}");
        assertPatchInvalid("{\"maxOutputTokens\":32000}");
    }

    @Test
    void rejectsIntegralTokenLimitOutsideSupportedRangeWithStableCode() throws Exception {
        assertTokenLimitInvalid("{\"contextWindowK\":0}");
        assertTokenLimitInvalid("{\"contextWindowK\":-1}");
        assertTokenLimitInvalid("{\"contextWindowK\":2147483648}");
    }

    private void assertPatchInvalid(String json) throws Exception {
        assertThatThrownBy(() -> mapper.parse(objectMapper.readTree(json)))
                .isInstanceOfSatisfying(AdminAiModelException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminAiModelErrorCode.AI_MODEL_PATCH_INVALID));
    }

    private void assertTokenLimitInvalid(String json) throws Exception {
        assertThatThrownBy(() -> mapper.parse(objectMapper.readTree(json)))
                .isInstanceOfSatisfying(AdminAiModelException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminAiModelErrorCode.AI_MODEL_TOKEN_LIMIT_INVALID));
    }
}
