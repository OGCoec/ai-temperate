package com.example.temperate.web.user.aiconversation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 AI 会话请求的推理强度和图片输出数量都只能进入约定的短整数边界。
 */
final class AiConversationResponseRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsMissingAndBoundaryReasoningEffortLevels() {
        assertThat(violations(null)).isZero();
        assertThat(violations((short) 1)).isZero();
        assertThat(violations((short) 5)).isZero();
    }

    @Test
    void rejectsOutOfRangeReasoningEffortLevels() {
        assertThat(violations((short) 0)).isOne();
        assertThat(violations((short) 6)).isOne();
    }

    @Test
    void defaultsMissingWebSearchModeToOffAndAcceptsEveryExplicitMode() {
        AiConversationResponseRequest defaulted = request(null, null);

        assertThat(defaulted.webSearchMode())
                .isEqualTo(AiConversationWebSearchMode.OFF);
        for (AiConversationWebSearchMode mode
                : AiConversationWebSearchMode.values()) {
            assertThat(validator.validate(request((short) 2, mode))).isEmpty();
        }
    }

    @Test
    void imageOutputCountDefaultsToOneAndUsesPrimitiveShortAccessor()
            throws Exception {
        AiConversationImageRequest request = objectMapper.readValue(
                "{\"aspect\":\"PORTRAIT\"}",
                AiConversationImageRequest.class);

        assertThat(request.aspect()).isEqualTo(AiConversationImageAspect.PORTRAIT);
        assertThat(request.outputCount()).isEqualTo((short) 1);
        assertThat(AiConversationImageRequest.class
                .getMethod("outputCount").getReturnType())
                .isEqualTo(short.class);
    }

    @Test
    void acceptsImageOutputCountBoundariesAndRejectsZeroAndEleven()
            throws Exception {
        assertThat(validator.validate(imageRequest((short) 1))).isEmpty();
        assertThat(validator.validate(imageRequest((short) 10))).isEmpty();
        assertThat(validator.validate(imageRequest((short) 0))).hasSize(1);
        assertThat(validator.validate(imageRequest((short) 11))).hasSize(1);
    }

    @Test
    void rejectsFractionalAndStringImageOutputCounts() {
        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"aspect\":\"SQUARE\",\"outputCount\":1.5}",
                AiConversationImageRequest.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"aspect\":\"SQUARE\",\"outputCount\":\"2\"}",
                AiConversationImageRequest.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    private int violations(Short level) {
        return validator.validate(request(level, null))
                .size();
    }

    private AiConversationImageRequest imageRequest(short outputCount)
            throws Exception {
        return objectMapper.readValue(
                "{\"aspect\":\"SQUARE\",\"outputCount\":"
                        + outputCount + "}",
                AiConversationImageRequest.class);
    }

    private static AiConversationResponseRequest request(
            Short level,
            AiConversationWebSearchMode webSearchMode) {
        return new AiConversationResponseRequest(
                "AAAAAAAAAAA",
                level,
                webSearchMode,
                new AiConversationInputRequest("hello", List.of()));
    }
}
