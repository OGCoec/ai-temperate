package com.example.temperate.web.user.aiconversation.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 验证图片运行时链接故障在 SSE 建立前会映射为不泄露内部信息的服务不可用响应。
 */
final class AiConversationRuntimeLinkageExceptionHandlerTest {

    @Test
    void runtimeLinkageFailureMapsToServiceUnavailable() {
        AiConversationExceptionHandler handler = new AiConversationExceptionHandler(
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        AiConversationException failure = new AiConversationException(
                AiConversationErrorCode.AI_RUNTIME_LINKAGE_FAILED,
                "AI 服务运行环境异常",
                false,
                new NoClassDefFoundError("test-only-missing-class"));

        var response = handler.handle(failure);

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code())
                .isEqualTo("AI_RUNTIME_LINKAGE_FAILED");
        assertThat(response.getBody().message())
                .isEqualTo("AI 服务运行环境异常");
    }
}
