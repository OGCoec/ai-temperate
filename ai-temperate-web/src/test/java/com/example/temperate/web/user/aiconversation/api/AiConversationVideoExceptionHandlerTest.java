package com.example.temperate.web.user.aiconversation.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

/**
 * 验证视频上游失败和OSS交付失败在SSE建立前分别映射为稳定的网关与服务错误。
 */
final class AiConversationVideoExceptionHandlerTest {

    private final AiConversationExceptionHandler handler =
            new AiConversationExceptionHandler(
                    Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    @ParameterizedTest
    @EnumSource(value = AiConversationErrorCode.class, names = {
            "AI_VIDEO_XAI_REJECTED",
            "AI_VIDEO_XAI_FAILED",
            "AI_VIDEO_XAI_EXPIRED",
            "AI_VIDEO_XAI_RESULT_UNCERTAIN"
    })
    void xaiVideoFailuresMapToBadGateway(AiConversationErrorCode code) {
        var response = handler.handle(new AiConversationException(
                code, "视频上游失败", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(code.name());
    }

    @Test
    void ossVideoTransferFailureMapsToServiceUnavailable() {
        var response = handler.handle(new AiConversationException(
                AiConversationErrorCode.AI_VIDEO_OSS_TRANSFER_FAILED,
                "视频无法保存", true));

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code())
                .isEqualTo("AI_VIDEO_OSS_TRANSFER_FAILED");
    }
}
