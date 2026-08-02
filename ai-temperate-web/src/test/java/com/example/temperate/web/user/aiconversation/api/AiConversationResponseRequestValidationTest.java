package com.example.temperate.web.user.aiconversation.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 AI 会话请求只接受 1 至 5 的可选短整数推理强度。
 */
final class AiConversationResponseRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

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

    private int violations(Short level) {
        return validator.validate(new AiConversationResponseRequest(
                        "AAAAAAAAAAA",
                        level,
                        new AiConversationInputRequest("hello", List.of())))
                .size();
    }
}
