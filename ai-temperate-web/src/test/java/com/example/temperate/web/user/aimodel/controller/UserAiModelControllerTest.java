package com.example.temperate.web.user.aimodel.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.user.aimodel.dto.UserAiModelPageResult;
import com.example.temperate.service.user.aimodel.dto.UserAiModelResult;
import com.example.temperate.service.user.aimodel.service.UserAiModelCatalogService;
import com.example.temperate.web.aimodel.AiModelPublicId;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 验证普通用户模型目录 Controller 只编排分页、公共 ID 和私有无缓存响应。
 */
final class UserAiModelControllerTest {

    @Test
    void exposesOptionalBoundedKeywordOnThePageEndpoint() throws NoSuchMethodException {
        Method list = UserAiModelController.class.getDeclaredMethod(
                "list",
                int.class,
                int.class,
                String.class);

        RequestParam keyword = list.getParameters()[2].getAnnotation(RequestParam.class);
        Size size = list.getParameters()[2].getAnnotation(Size.class);

        assertThat(keyword.required()).isFalse();
        assertThat(size.max()).isEqualTo(128);
    }

    @Test
    void exposesIndependentModelNameMatchTokensInTheCatalogResponse() {
        assertThat(Arrays.stream(UserAiModelResult.class.getRecordComponents())
                .map(RecordComponent::getName))
                .contains("modelNameMatchedTokens", "descriptionMatchedTokens", "icon");
    }

    @Test
    void returnsEnabledModelPageWithNoStoreResponse() {
        UserAiModelCatalogService service = mock(UserAiModelCatalogService.class);
        UserAiModelPageResult result = new UserAiModelPageResult(
                List.of(model()),
                1,
                20,
                1,
                1,
                false,
                false);
        when(service.list(1, 20, " mini ")).thenReturn(result);
        UserAiModelController controller = new UserAiModelController(service);

        ResponseEntity<UserAiModelPageResult> response = controller.list(1, 20, " mini ");

        assertThat(response.getBody()).isEqualTo(result);
        assertThat(response.getBody().models()).singleElement()
                .extracting(UserAiModelResult::icon)
                .isEqualTo("https://example.test/model.svg");
        assertThat(response.getHeaders().getCacheControl())
                .contains("private")
                .contains("no-store");
        verify(service).list(1, 20, " mini ");
    }

    @Test
    void delegatesValidatedPublicIdToDetailService() {
        UserAiModelCatalogService service = mock(UserAiModelCatalogService.class);
        UserAiModelResult result = model();
        when(service.detail(result.publicId())).thenReturn(result);
        UserAiModelController controller = new UserAiModelController(service);

        ResponseEntity<UserAiModelResult> response =
                controller.detail(new AiModelPublicId(result.publicId()));

        assertThat(response.getBody()).isEqualTo(result);
        assertThat(response.getBody().icon()).isEqualTo("https://example.test/model.svg");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        verify(service).detail(result.publicId());
    }

    private static UserAiModelResult model() {
        return new UserAiModelResult(
                new PublicIdCodec().encode(42L),
                "gpt-5.6",
                List.of("gpt"),
                "openai",
                "完整模型描述",
                List.of("模型"),
                "https://example.test/model.svg",
                List.of("代码"),
                BigDecimal.ONE,
                new BigDecimal("0.25000000"),
                new BigDecimal("4.00000000"),
                List.of(AiModelCapabilityCode.RESPONSES),
                List.of((short) 1, (short) 2, (short) 3, (short) 4, (short) 5),
                (short) 2);
    }
}
