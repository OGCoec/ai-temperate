package com.example.temperate.web.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelDetailResult;
import com.example.temperate.service.admin.aimodel.exception.AdminAiModelErrorCode;
import com.example.temperate.service.admin.aimodel.exception.AdminAiModelException;
import com.example.temperate.service.admin.aimodel.service.AdminAiModelService;
import com.example.temperate.web.admin.aimodel.AdminAiModelMergePatchMapper;
import com.example.temperate.web.admin.aimodel.AiModelPublicId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/**
 * 验证 AI 模型详情和 Merge Patch Controller 正确编排强 ETag、无缓存响应和强类型命令。
 */
@ExtendWith(MockitoExtension.class)
final class AdminAiModelControllerPatchTest {

    @Mock
    private AdminAiModelService service;

    private ObjectMapper objectMapper;
    private AdminAiModelController controller;
    private String publicId;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        controller = new AdminAiModelController(
                service,
                new AdminAiModelMergePatchMapper(objectMapper));
        publicId = new PublicIdCodec().encode(123L);
    }

    @Test
    void patchRequiresVersionAndReturnsIncrementedEtag() throws Exception {
        when(service.patch(eq(publicId), eq(3L), any()))
                .thenReturn(detail(4L));

        ResponseEntity<AdminAiModelDetailResult> response = controller.patch(
                new AiModelPublicId(publicId),
                "\"v3\"",
                objectMapper.readTree("""
                        {
                          "description": null,
                          "capabilities": ["RESPONSES", "IMAGE"]
                        }
                        """));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"v4\"");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        verify(service).patch(eq(publicId), eq(3L), any());
    }

    @Test
    void missingIfMatchIsAControlledPreconditionFailure() throws Exception {
        assertThatThrownBy(() -> controller.patch(
                new AiModelPublicId(publicId),
                null,
                objectMapper.readTree("{\"vendor\":\"openai\"}")))
                .isInstanceOfSatisfying(AdminAiModelException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminAiModelErrorCode.AI_MODEL_VERSION_REQUIRED));
    }

    private AdminAiModelDetailResult detail(long rowVersion) {
        return new AdminAiModelDetailResult(
                publicId,
                "gpt-5.6",
                null,
                null,
                null,
                List.of("chat"),
                "openai",
                BigDecimal.ONE,
                BigDecimal.TWO,
                true,
                List.of(AiModelCapabilityCode.RESPONSES),
                List.of(AiModelCapabilityCode.values()),
                rowVersion,
                LocalDate.of(2026, 7, 26),
                LocalDate.of(2026, 7, 26));
    }
}
