package com.example.temperate.service.user.aiconversation.context.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentCategory;
import com.example.temperate.service.user.aiconversation.attachment.config.AiConversationAttachmentProperties;
import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationTurn;
import com.example.temperate.service.user.aiconversation.context.AiConversationTurnState;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证文本、图片、音频和视频使用同一保守估算器进入会话快照，而非使用计费 Usage 累加。
 */
final class ConservativeAiConversationTokenEstimatorTest {

    private ConservativeAiConversationTokenEstimator estimator;

    @BeforeEach
    void setUp() {
        AiConversationProperties properties = mock(AiConversationProperties.class);
        when(properties.imageEstimatedTokens()).thenReturn(2_048);
        AiConversationAttachmentProperties attachments =
                mock(AiConversationAttachmentProperties.class);
        when(attachments.audioTokensPerMib()).thenReturn(1_000);
        when(attachments.audioFixedTokens()).thenReturn(100);
        when(attachments.videoTokensPerMib()).thenReturn(2_000);
        when(attachments.videoFixedTokens()).thenReturn(200);
        estimator = new ConservativeAiConversationTokenEstimator(
                properties, attachments);
    }

    @Test
    void estimatesUtf8TextAndLongTextDeterministically() {
        long chinese = estimator.estimateCurrentInput(
                new AiConversationContent("你好，世界", List.of()));
        long repeated = estimator.estimateCurrentInput(
                new AiConversationContent("context ".repeat(2_000), List.of()));

        assertThat(chinese).isPositive();
        assertThat(repeated).isGreaterThan(chinese);
        assertThat(estimator.estimateCurrentInput(
                new AiConversationContent("你好，世界", List.of())))
                .isEqualTo(chinese);
    }

    @Test
    void estimatesMixedMediaWithStartedMibRounding() {
        AiConversationContent mixed = new AiConversationContent(
                "media",
                List.of(
                        attachment("image", AiConversationAttachmentCategory.IMAGE, 1L),
                        attachment("audio", AiConversationAttachmentCategory.AUDIO,
                                1024L * 1024L + 1L),
                        attachment("video", AiConversationAttachmentCategory.VIDEO,
                                1024L * 1024L)));
        long textOnly = estimator.estimateCurrentInput(
                new AiConversationContent("media", List.of()));

        assertThat(estimator.estimateCurrentInput(mixed) - textOnly)
                .isEqualTo(2_048L + 2_100L + 2_200L);
    }

    @Test
    void contextIncludesSystemSummariesAndStoredTurnEstimate() {
        AiConversationTurn turn = new AiConversationTurn(
                "1",
                1L,
                null,
                new AiConversationContent("ignored-user", List.of()),
                new AiConversationContent("ignored-assistant", List.of()),
                AiConversationTurnState.PERSISTED,
                null,
                321L);

        long empty = estimator.estimateContext("system", null, null, List.of());
        long complete = estimator.estimateContext(
                "system", "durable-summary", "stopped-summary", List.of(turn));

        assertThat(complete).isGreaterThanOrEqualTo(empty + 321L);
    }

    private static AiConversationAttachment attachment(
            String id,
            AiConversationAttachmentCategory category,
            long sizeBytes) {
        return AiConversationAttachment.available(
                id,
                id + ".bin",
                "application/octet-stream",
                sizeBytes,
                category,
                "https://example.test/" + id);
    }
}
