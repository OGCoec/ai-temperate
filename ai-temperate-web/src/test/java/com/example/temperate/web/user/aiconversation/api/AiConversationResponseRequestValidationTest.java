package com.example.temperate.web.user.aiconversation.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
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

    private int violations(Short level) {
        return validator.validate(request(level, null))
                .size();
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
