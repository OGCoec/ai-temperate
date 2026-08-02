package com.example.temperate.mapper.ai;

import com.example.temperate.model.ai.entity.AiConversationMessage;
import com.example.temperate.model.ai.entity.AiConversationMessageHistoryRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 提供消息 ID 预取、完整消息显式插入和按检查点有界读取的 MyBatis 持久化契约。
 */
@Mapper
public interface AiConversationMessageMapper {

    long reserveMessageId();

    int insert(AiConversationMessage message);

    AiConversationMessage findByIdAndConversationId(
            @Param("id") long id,
            @Param("conversationId") byte[] conversationId);

    List<AiConversationMessage> findAfterMessageId(
            @Param("conversationId") byte[] conversationId,
            @Param("afterMessageId") long afterMessageId,
            @Param("limit") int limit);

    List<AiConversationMessage> findCompactionRange(
            @Param("conversationId") byte[] conversationId,
            @Param("afterMessageId") long afterMessageId,
            @Param("cutoffMessageId") long cutoffMessageId,
            @Param("limit") int limit);

    Long findLatestPersistedMessageId(@Param("conversationId") byte[] conversationId);

    List<AiConversationMessageHistoryRow> findOwnedHistoryPage(
            @Param("conversationId") byte[] conversationId,
            @Param("loginIdentityId") long loginIdentityId,
            @Param("beforeMessageId") Long beforeMessageId,
            @Param("limit") int limit);
}
