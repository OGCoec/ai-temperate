package com.example.temperate.mapper.ai;

import com.example.temperate.model.ai.entity.AiConversation;
import com.example.temperate.model.ai.entity.AiConversationSidebarRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 提供 AI 会话创建、归属读取、侧栏快照更新和压缩检查点 CAS 更新的 MyBatis 持久化契约。
 */
@Mapper
public interface AiConversationMapper {

    int insert(AiConversation conversation);

    AiConversation findActiveOwned(
            @Param("conversationId") byte[] conversationId,
            @Param("loginIdentityId") long loginIdentityId);

    AiConversation findById(@Param("conversationId") byte[] conversationId);

    List<AiConversationSidebarRow> findActivePage(
            @Param("loginIdentityId") long loginIdentityId,
            @Param("beforeLastMessageId") Long beforeLastMessageId,
            @Param("beforeConversationId") byte[] beforeConversationId,
            @Param("limit") int limit);

    int updateAfterPersistedMessage(
            @Param("conversationId") byte[] conversationId,
            @Param("messageId") long messageId,
            @Param("initialTitle") String initialTitle);

    int updateCompactionCompareAndSet(
            @Param("conversationId") byte[] conversationId,
            @Param("expectedLastCompactedMessageId") Long expectedLastCompactedMessageId,
            @Param("lastCompactedMessageId") long lastCompactedMessageId,
            @Param("compactedContextJson") String compactedContextJson);
}
