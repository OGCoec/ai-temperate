package com.example.temperate.service.user.aiconversation.compaction.model.impl;

import com.example.temperate.service.user.aiconversation.compaction.model.AiConversationCompactionModelCatalog;
import com.example.temperate.service.user.aiconversation.compaction.model.AiConversationCompactionModelRef;
import com.example.temperate.service.user.aiconversation.compaction.model.AiConversationCompactionModelSelector;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 使用 Rendezvous 一致性哈希从全部启用模型中选择会话压缩模型，不保存轮询或请求级状态。
 */
@Service
public final class AiConversationCompactionModelSelectorImpl
        implements AiConversationCompactionModelSelector {

    private final AiConversationCompactionModelCatalog catalog;

    public AiConversationCompactionModelSelectorImpl(
            AiConversationCompactionModelCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog);
    }

    @Override
    public AiConversationCompactionModelRef selectRequired(
            String conversationPublicId) {
        if (conversationPublicId == null || conversationPublicId.isBlank()) {
            throw new IllegalArgumentException(
                    "Conversation public ID is required for model selection.");
        }
        List<AiConversationCompactionModelRef> candidates =
                catalog.enabledModels();
        if (candidates.isEmpty()) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE,
                    "当前没有管理员启用的模型",
                    true);
        }

        MessageDigest digest = sha256();
        byte[] conversationBytes = conversationPublicId
                .getBytes(StandardCharsets.UTF_8);
        AiConversationCompactionModelRef selected = null;
        byte[] selectedScore = null;
        for (AiConversationCompactionModelRef candidate : candidates) {
            digest.reset();
            digest.update(conversationBytes);
            digest.update((byte) 0);
            byte[] score = digest.digest(ByteBuffer.allocate(Long.BYTES)
                    .putLong(candidate.id())
                    .array());
            if (selected == null
                    || compareUnsigned(score, selectedScore) > 0
                    || (compareUnsigned(score, selectedScore) == 0
                    && candidate.id() < selected.id())) {
                selected = candidate;
                selectedScore = score;
            }
        }
        return selected;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        for (int index = 0; index < left.length; index++) {
            int comparison = Integer.compare(
                    Byte.toUnsignedInt(left[index]),
                    Byte.toUnsignedInt(right[index]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }
}
