package com.example.temperate.service.user.aiconversation.context.impl;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentState;
import com.example.temperate.service.user.aiconversation.attachment.config.AiConversationAttachmentProperties;
import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationTokenEstimator;
import com.example.temperate.service.user.aiconversation.context.AiConversationTurn;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 使用保守文字、图片和按 MiB 递增的音视频规则估算上下文，最终费用仍只以上游 Usage 为准。
 */
@Service
public final class ConservativeAiConversationTokenEstimator
        implements AiConversationTokenEstimator {

    private static final long MESSAGE_OVERHEAD_TOKENS = 12L;
    private static final long MIB = 1024L * 1024L;
    private final AiConversationProperties properties;
    private final AiConversationAttachmentProperties attachmentProperties;

    public ConservativeAiConversationTokenEstimator(
            AiConversationProperties properties,
            AiConversationAttachmentProperties attachmentProperties) {
        this.properties = Objects.requireNonNull(properties);
        this.attachmentProperties = Objects.requireNonNull(attachmentProperties);
    }

    @Override
    public long estimate(
            String systemPrompt,
            String durableCompactionJson,
            String ephemeralCompactionJson,
            List<AiConversationTurn> turns,
            AiConversationContent currentInput) {
        long total = estimateText(systemPrompt) + MESSAGE_OVERHEAD_TOKENS;
        total = Math.addExact(total, estimateText(durableCompactionJson));
        total = Math.addExact(total, estimateText(ephemeralCompactionJson));
        for (AiConversationTurn turn : turns) {
            total = Math.addExact(total, estimateContent(turn.user()));
            total = Math.addExact(total, estimateContent(turn.assistant()));
            total = Math.addExact(total, MESSAGE_OVERHEAD_TOKENS * 2L);
        }
        total = Math.addExact(total, estimateContent(currentInput));
        return Math.addExact(total, MESSAGE_OVERHEAD_TOKENS);
    }

    private long estimateContent(AiConversationContent content) {
        long total = estimateText(content.text());
        for (AiConversationAttachment attachment : content.attachments()) {
            if (attachment.state() != AiConversationAttachmentState.AVAILABLE) {
                continue;
            }
            long attachmentTokens = switch (attachment.category()) {
                case IMAGE -> properties.imageEstimatedTokens();
                case AUDIO -> perStartedMib(
                        attachment.sizeBytesAsLong(),
                        attachmentProperties.audioTokensPerMib(),
                        attachmentProperties.audioFixedTokens());
                case VIDEO -> perStartedMib(
                        attachment.sizeBytesAsLong(),
                        attachmentProperties.videoTokensPerMib(),
                        attachmentProperties.videoFixedTokens());
                case DOCUMENT, ARCHIVE, OTHER -> 0L;
            };
            total = Math.addExact(total, attachmentTokens);
        }
        return total;
    }

    private static long perStartedMib(
            long sizeBytes,
            long tokensPerMib,
            long fixedTokens) {
        long startedMib = Math.floorDiv(Math.addExact(sizeBytes, MIB - 1L), MIB);
        return Math.addExact(Math.multiplyExact(startedMib, tokensPerMib), fixedTokens);
    }

    private static long estimateText(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        long bytes = value.getBytes(StandardCharsets.UTF_8).length;
        return Math.max(1L, Math.floorDiv(bytes + 2L, 3L));
    }
}
