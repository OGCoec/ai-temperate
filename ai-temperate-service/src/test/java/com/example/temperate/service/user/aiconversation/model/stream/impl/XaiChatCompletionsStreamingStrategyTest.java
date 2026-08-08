package com.example.temperate.service.user.aiconversation.model.stream.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelClient;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelRequest;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * 验证 xAI Chat 在建立上游订阅前拒绝第四、第五档推理强度，避免先调用再由供应商返回 422。
 */
final class XaiChatCompletionsStreamingStrategyTest {

    @Test
    void rejectsUnsupportedReasoningBeforeCallingModelClient() {
        AiConversationModelClient modelClient = mock(AiConversationModelClient.class);
        XaiChatCompletionsStreamingStrategy strategy =
                new XaiChatCompletionsStreamingStrategy(modelClient);
        AiConversationPromptSnapshot prompt = new AiConversationPromptSnapshot(
                "system",
                null,
                null,
                List.of(),
                new AiConversationContent("hello", List.of()),
                "generation",
                1L,
                false);
        AiConversationStreamingRequest request = new AiConversationStreamingRequest(
                new AiConversationModelRequest(
                        AiModelProvider.XAI,
                        "grok-test",
                        128L,
                        AiConversationReasoningEffort.EXTRA_HIGH,
                        prompt),
                AiConversationWebSearchMode.OFF);

        StepVerifier.create(strategy.stream(request))
                .expectErrorSatisfies(failure -> {
                    assertThat(failure).isInstanceOf(AiConversationException.class);
                    assertThat(((AiConversationException) failure).code())
                            .isEqualTo(AiConversationErrorCode.AI_REQUEST_INVALID);
                })
                .verify();
        verifyNoInteractions(modelClient);
    }
}
