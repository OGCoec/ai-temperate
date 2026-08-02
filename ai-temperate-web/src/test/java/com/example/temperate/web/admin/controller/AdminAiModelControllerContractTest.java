package com.example.temperate.web.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.domain.AiModelSortDirection;
import com.example.temperate.service.admin.aimodel.domain.AiModelSortPriority;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelCreateCommand;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelDetailResult;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelPageResult;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelResult;
import com.example.temperate.service.admin.aimodel.exception.AdminAiModelErrorCode;
import com.example.temperate.service.admin.aimodel.exception.AdminAiModelException;
import com.example.temperate.web.config.HttpJsonLong2StringConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 验证 AI 模型 Controller 位于管理员安全命名空间，使用 Merge Patch 编辑且不存在物理删除路由。
 */
final class AdminAiModelControllerContractTest {

    @Test
    void remainsProxyableForMethodValidation() {
        assertThat(Modifier.isFinal(AdminAiModelController.class.getModifiers())).isFalse();
    }

    @Test
    void staysUnderAdminNamespaceWithoutPutOrDeleteMappings() {
        RequestMapping mapping = AdminAiModelController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping.value()).containsExactly("/api/admin/ai-models");

        Method[] methods = AdminAiModelController.class.getDeclaredMethods();
        assertThat(Arrays.stream(methods)
                .noneMatch(method -> method.isAnnotationPresent(PutMapping.class)))
                .isTrue();
        assertThat(Arrays.stream(methods)
                .noneMatch(method -> method.isAnnotationPresent(DeleteMapping.class)))
                .isTrue();
    }

    @Test
    void exposesMergePatchWithoutIntroducingPhysicalDelete() {
        Method[] methods = AdminAiModelController.class.getDeclaredMethods();

        assertThat(Arrays.stream(methods)
                .filter(method -> method.isAnnotationPresent(PatchMapping.class))
                .map(Method::getName))
                .contains("patch", "setStatus");
        assertThat(Arrays.stream(methods)
                .noneMatch(method -> method.isAnnotationPresent(DeleteMapping.class)))
                .isTrue();
    }

    @Test
    void exposesPageHelperPaginationAndSafeSortDefaults() throws NoSuchMethodException {
        Method list = AdminAiModelController.class.getDeclaredMethod(
                "list",
                int.class,
                int.class,
                String.class,
                Boolean.class,
                AiModelSortPriority.class,
                AiModelSortDirection.class,
                HttpServletResponse.class);

        RequestParam pageNum = list.getParameters()[0].getAnnotation(RequestParam.class);
        RequestParam pageSize = list.getParameters()[1].getAnnotation(RequestParam.class);
        RequestParam keyword = list.getParameters()[2].getAnnotation(RequestParam.class);
        Size keywordSize = list.getParameters()[2].getAnnotation(Size.class);
        RequestParam enabled = list.getParameters()[3].getAnnotation(RequestParam.class);
        RequestParam sortPriority = list.getParameters()[4].getAnnotation(RequestParam.class);
        RequestParam direction = list.getParameters()[5].getAnnotation(RequestParam.class);

        assertThat(pageNum.defaultValue()).isEqualTo("1");
        assertThat(pageSize.defaultValue()).isEqualTo("50");
        assertThat(keyword.required()).isFalse();
        assertThat(keywordSize.max()).isEqualTo(128);
        assertThat(enabled.required()).isFalse();
        assertThat(sortPriority.defaultValue()).isEqualTo("INPUT_FIRST");
        assertThat(direction.defaultValue()).isEqualTo("ASC");
        assertThat(Arrays.stream(AdminAiModelPageResult.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly(
                        "models",
                        "pageNum",
                        "pageSize",
                        "total",
                        "pages",
                        "hasPrevious",
                        "hasNext")
                .doesNotContain("nextCursor");
    }

    @Test
    void exposesEveryCapabilityCodeInRequestValidationAndOpenApi() throws NoSuchMethodException {
        Method capabilitiesAccessor =
                AdminAiModelController.CreateRequest.class.getDeclaredMethod("capabilities");
        Size size = capabilitiesAccessor.getAnnotation(Size.class);
        ArraySchema arraySchema = capabilitiesAccessor.getAnnotation(ArraySchema.class);
        String[] capabilityCodes = Arrays.stream(AiModelCapabilityCode.values())
                .map(Enum::name)
                .toArray(String[]::new);

        assertThat(size).isNotNull();
        assertThat(size.max()).isEqualTo(capabilityCodes.length);
        assertThat(arraySchema).isNotNull();
        assertThat(arraySchema.schema().allowableValues()).containsExactly(capabilityCodes);
    }

    @Test
    void createRequestUsesIconPublicIdAndDoesNotExposeLegacyIconUrlInput() {
        assertThat(Arrays.stream(AdminAiModelController.CreateRequest.class.getRecordComponents())
                .map(component -> component.getName()))
                .contains(
                        "iconPublicId",
                        "inputRatio",
                        "cachedInputRatio",
                        "outputRatio",
                        "contextWindowK",
                        "maxOutputK")
                .doesNotContain("icon", "contextWindowTokens", "maxOutputTokens");
    }

    @Test
    void createRequestStrictlyConvertsIntegralKValuesToRawTokens() throws Exception {
        AdminAiModelController.CreateRequest request = new ObjectMapper().readValue(
                validCreateJson("256", "32"),
                AdminAiModelController.CreateRequest.class);

        AdminAiModelCreateCommand command = request.toCommand();

        assertThat(command.contextWindowTokens()).isEqualTo(256000L);
        assertThat(command.maxOutputTokens()).isEqualTo(32000L);
    }

    @Test
    void createRequestOpenApiDeclaresRequiredBoundedIntegerKFields()
            throws NoSuchMethodException {
        for (String accessor : java.util.List.of("contextWindowK", "maxOutputK")) {
            Schema schema = AdminAiModelController.CreateRequest.class
                    .getDeclaredMethod(accessor)
                    .getAnnotation(Schema.class);

            assertThat(schema.type()).isEqualTo("integer");
            assertThat(schema.minimum()).isEqualTo("1");
            assertThat(schema.maximum()).isEqualTo("2147483647");
            assertThat(schema.requiredMode()).isEqualTo(Schema.RequiredMode.REQUIRED);
        }
    }

    @Test
    void createRequestRejectsMissingCoercedFractionalAndOutOfRangeKValues()
            throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        var missingContextWindow = objectMapper.readTree(validCreateJson("256", "32"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) missingContextWindow)
                .remove("contextWindowK");
        AdminAiModelController.CreateRequest missingRequest = objectMapper.treeToValue(
                missingContextWindow,
                AdminAiModelController.CreateRequest.class);
        assertThatThrownBy(missingRequest::toCommand)
                .isInstanceOfSatisfying(AdminAiModelException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminAiModelErrorCode.AI_MODEL_TOKEN_LIMIT_INVALID));

        for (String[] values : new String[][] {
                {"null", "32"},
                {"\"256\"", "32"},
                {"256.0", "32"},
                {"2.56e2", "32"},
                {"true", "32"},
                {"0", "32"},
                {"-1", "32"},
                {"2147483648", "32"},
                {"256", "null"}
        }) {
            assertThatThrownBy(() -> new ObjectMapper()
                    .readValue(
                            validCreateJson(values[0], values[1]),
                            AdminAiModelController.CreateRequest.class)
                    .toCommand())
                    .isInstanceOfSatisfying(AdminAiModelException.class, exception ->
                            assertThat(exception.code())
                                    .isEqualTo(
                                            AdminAiModelErrorCode.AI_MODEL_TOKEN_LIMIT_INVALID));
        }
    }

    @Test
    void adminResponsesExposeRawTokenLongsAndKIntegers() {
        assertThat(Arrays.stream(AdminAiModelResult.class.getRecordComponents())
                .filter(component -> component.getName().equals("contextWindowTokens")
                        || component.getName().equals("maxOutputTokens"))
                .map(component -> component.getType().getName()))
                .containsExactly(Long.class.getName(), Long.class.getName());
        assertThat(Arrays.stream(AdminAiModelResult.class.getRecordComponents())
                .map(component -> component.getName()))
                .contains("modelNameMatchedTokens", "descriptionMatchedTokens");
        assertThat(Arrays.stream(AdminAiModelResult.class.getRecordComponents())
                .filter(component -> component.getName().endsWith("K"))
                .map(component -> component.getType().getName()))
                .containsExactly(Integer.class.getName(), Integer.class.getName());
        assertThat(Arrays.stream(AdminAiModelDetailResult.class.getRecordComponents())
                .map(component -> component.getName()))
                .contains("contextWindowTokens", "contextWindowK", "maxOutputTokens", "maxOutputK");
    }

    @Test
    void httpJsonSerializesRawTokenLongsAsStringsAndKValuesAsIntegers()
            throws Exception {
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules());
        List<HttpMessageConverter<?>> converters = List.of(converter);
        new HttpJsonLong2StringConfiguration().extendMessageConverters(converters);
        AdminAiModelResult result = new AdminAiModelResult(
                "AAAAAAAAAAE",
                "gpt-5.6",
                List.of("gpt"),
                null,
                List.of(),
                null,
                null,
                List.of(),
                "openai",
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                256000L,
                256,
                32000L,
                32,
                false,
                List.of(AiModelCapabilityCode.RESPONSES),
                LocalDate.of(2026, 7, 30),
                LocalDate.of(2026, 7, 30));

        String json = converter.getObjectMapper().writeValueAsString(result);

        assertThat(json)
                .contains("\"modelNameMatchedTokens\":[\"gpt\"]")
                .contains("\"contextWindowTokens\":\"256000\"")
                .contains("\"contextWindowK\":256")
                .contains("\"maxOutputTokens\":\"32000\"")
                .contains("\"maxOutputK\":32");
    }

    @Test
    void createRequestRejectsLegacyIconUrlInsteadOfSilentlyIgnoringIt() {
        String json = """
                {
                  "modelName": "gpt-5.6",
                  "description": "test",
                  "icon": "https://example.test/legacy.png",
                  "iconPublicId": null,
                  "tags": ["chat"],
                  "vendor": "openai",
                  "inputRatio": 1,
                  "cachedInputRatio": 0.1,
                  "outputRatio": 1,
                  "contextWindowK": 256,
                  "maxOutputK": 32,
                  "enabled": false,
                  "capabilities": ["RESPONSES"]
                }
                """;

        assertThatThrownBy(() -> new ObjectMapper().readValue(
                json,
                AdminAiModelController.CreateRequest.class))
                .hasRootCauseInstanceOf(AdminAiModelException.class);
    }

    private static String validCreateJson(String contextWindowK, String maxOutputK) {
        return """
                {
                  "modelName": "gpt-5.6",
                  "description": "test",
                  "iconPublicId": null,
                  "tags": ["chat"],
                  "vendor": "openai",
                  "inputRatio": 1,
                  "cachedInputRatio": 0.1,
                  "outputRatio": 1,
                  "contextWindowK": %s,
                  "maxOutputK": %s,
                  "enabled": false,
                  "capabilities": ["RESPONSES"]
                }
                """.formatted(contextWindowK, maxOutputK);
    }
}
