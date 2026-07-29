package com.example.temperate.web.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.domain.AiModelSortDirection;
import com.example.temperate.service.admin.aimodel.domain.AiModelSortPriority;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelPageResult;
import com.example.temperate.service.admin.aimodel.exception.AdminAiModelException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
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
                .contains("iconPublicId", "inputRatio", "cachedInputRatio", "outputRatio")
                .doesNotContain("icon");
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
                  "enabled": false,
                  "capabilities": ["RESPONSES"]
                }
                """;

        assertThatThrownBy(() -> new ObjectMapper().readValue(
                json,
                AdminAiModelController.CreateRequest.class))
                .hasRootCauseInstanceOf(AdminAiModelException.class);
    }
}
