package com.example.temperate.service.user.aiconversation.generation.worker.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * 验证图片子流失败策略区分实例级链接故障与普通单路上游失败。
 */
final class AiConversationGenerationWorkerImageFailurePolicyTest {

    @Test
    void runtimeLinkageFailureEscapesTheChildStreamAndCancelsSiblingMerge() {
        AiConversationException failure = new AiConversationException(
                AiConversationErrorCode.AI_RUNTIME_LINKAGE_FAILED,
                "AI 服务运行环境异常",
                false,
                new NoClassDefFoundError("AiConversationGeneratedImagePhase"));

        StepVerifier.create(AiConversationGenerationWorkerImpl
                        .recoverImageChildFailure((short) 2, failure))
                .expectErrorSatisfies(actual -> assertThat(actual).isSameAs(failure))
                .verify();
    }

    @Test
    void ordinaryUpstreamFailureBecomesOneSlotFailureEvent() {
        IllegalStateException failure = new IllegalStateException(
                "upstream disconnected");

        StepVerifier.create(AiConversationGenerationWorkerImpl
                        .recoverImageChildFailure((short) 3, failure))
                .assertNext(event -> {
                    assertThat(event)
                            .isInstanceOf(AiConversationModelEvent.ImageFailure.class);
                    AiConversationModelEvent.ImageFailure imageFailure =
                            (AiConversationModelEvent.ImageFailure) event;
                    assertThat(imageFailure.outputIndex()).isEqualTo((short) 3);
                    assertThat(imageFailure.cause()).isSameAs(failure);
                })
                .verifyComplete();
    }
}
