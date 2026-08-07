package com.example.temperate.service.user.aiconversation.context;

import java.util.List;
import java.util.Optional;

/**
 * 定义 AI 会话 Redis Hash 的读取、绝对 TTL 创建、流片追加和压缩替换边界。
 */
public interface AiConversationContextStore {

    Optional<AiConversationContextSnapshot> find(String conversationPublicId);

    void invalidate(String conversationPublicId);

    AiConversationContextWriteOutcome create(
            String conversationPublicId,
            AiConversationContextSnapshot snapshot);

    AiConversationEphemeralStart appendEphemeralUser(
            String conversationPublicId,
            String generation,
            String usagePublicId,
            AiConversationContent user);

    AiConversationContextWriteOutcome appendAssistantChunks(
            String conversationPublicId,
            String generation,
            long ephemeralOrdinal,
            int firstChunkNumber,
            List<String> chunks);

    AiConversationContextWriteOutcome saveInterruptedTurn(
            String conversationPublicId,
            String generation,
            long ephemeralOrdinal,
            List<String> assistantChunks,
            AiConversationInterruptionSource interruptionSource);

    AiConversationContextWriteOutcome commitPersistedTurn(
            String conversationPublicId,
            String generation,
            long messageId,
            long ephemeralOrdinal,
            AiConversationContent user,
            AiConversationContent assistant);

    AiConversationContextWriteOutcome replaceDurableCompaction(
            String conversationPublicId,
            String generation,
            long cutoffMessageId,
            String compactedContextJson);

    AiConversationContextWriteOutcome replaceEphemeralCompaction(
            String conversationPublicId,
            String generation,
            String compactedContextJson,
            long throughEphemeralOrdinal,
            List<Long> compactedEphemeralOrdinals);
}
