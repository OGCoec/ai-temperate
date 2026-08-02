package com.example.temperate.service.user.aiconversation.compaction.impl;

import com.example.temperate.mapper.ai.AiConversationMapper;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionPersistenceService;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 使用旧检查点作为 CAS 条件更新持久化压缩摘要，防止两个压缩任务互相覆盖。
 */
@Service
public final class AiConversationCompactionPersistenceServiceImpl
        implements AiConversationCompactionPersistenceService {

    private final AiConversationMapper conversationMapper;

    public AiConversationCompactionPersistenceServiceImpl(
            AiConversationMapper conversationMapper) {
        this.conversationMapper = Objects.requireNonNull(conversationMapper);
    }

    @Override
    @Transactional
    public boolean compareAndSet(
            byte[] conversationId,
            Long expectedCheckpoint,
            long cutoffMessageId,
            String compactedContextJson) {
        return conversationMapper.updateCompactionCompareAndSet(
                conversationId,
                expectedCheckpoint,
                cutoffMessageId,
                compactedContextJson) == 1;
    }
}
