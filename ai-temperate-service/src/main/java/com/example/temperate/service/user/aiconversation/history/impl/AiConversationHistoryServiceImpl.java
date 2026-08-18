package com.example.temperate.service.user.aiconversation.history.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.ai.AiConversationMapper;
import com.example.temperate.mapper.ai.AiConversationMessageMapper;
import com.example.temperate.model.ai.entity.AiConversation;
import com.example.temperate.model.ai.entity.AiConversationMessageHistoryRow;
import com.example.temperate.model.ai.entity.AiConversationSidebarRow;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.history.AiConversationCursorCodec;
import com.example.temperate.service.user.aiconversation.history.AiConversationHistoryMessage;
import com.example.temperate.service.user.aiconversation.history.AiConversationHistoryPage;
import com.example.temperate.service.user.aiconversation.history.AiConversationHistoryService;
import com.example.temperate.service.user.aiconversation.history.AiConversationPage;
import com.example.temperate.service.user.aiconversation.history.AiConversationSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 从 PostgreSQL 分页读取当前用户的有效会话和完整消息历史，并在边界统一转换公共 ID 与附件 JSON。
 *
 * <p>该实现刻意不读取 Redis：中断回答只属于短期模型上下文，重新打开会话时只能看到已经完成结算并
 * 持久化的消息。</p>
 */
@Service
public final class AiConversationHistoryServiceImpl
        implements AiConversationHistoryService {

    private static final int MAX_CONVERSATION_PAGE_SIZE = 50;
    private static final int MAX_MESSAGE_PAGE_SIZE = 100;
    private static final TypeReference<List<AiConversationAttachment>> ATTACHMENT_LIST =
            new TypeReference<>() { };

    private final AiConversationMapper conversationMapper;
    private final AiConversationMessageMapper messageMapper;
    private final HybridBase64UrlCodec hybridPublicIds;
    private final PublicIdCodec longPublicIds;
    private final AiConversationCursorCodec cursorCodec;
    private final ObjectMapper objectMapper;

    public AiConversationHistoryServiceImpl(
            AiConversationMapper conversationMapper,
            AiConversationMessageMapper messageMapper,
            HybridBase64UrlCodec hybridPublicIds,
            PublicIdCodec longPublicIds,
            AiConversationCursorCodec cursorCodec,
            ObjectMapper objectMapper) {
        this.conversationMapper = Objects.requireNonNull(conversationMapper);
        this.messageMapper = Objects.requireNonNull(messageMapper);
        this.hybridPublicIds = Objects.requireNonNull(hybridPublicIds);
        this.longPublicIds = Objects.requireNonNull(longPublicIds);
        this.cursorCodec = Objects.requireNonNull(cursorCodec);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    @Transactional(readOnly = true)
    public AiConversationPage list(long userId, String cursor, int pageSize) {
        requirePageSize(pageSize, MAX_CONVERSATION_PAGE_SIZE);
        AiConversationCursorCodec.Cursor decoded = decodeCursor(cursor);
        List<AiConversationSidebarRow> rows = conversationMapper.findActivePage(
                userId,
                decoded == null ? null : decoded.lastMessageId(),
                decoded == null ? null : decoded.conversationId(),
                pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<AiConversationSidebarRow> selected = hasMore
                ? rows.subList(0, pageSize)
                : rows;
        List<AiConversationSummary> conversations = selected.stream()
                .map(this::summary)
                .toList();
        String nextCursor = hasMore && !selected.isEmpty()
                ? cursorCodec.encode(
                        selected.get(selected.size() - 1).getLastMessageId(),
                        selected.get(selected.size() - 1).getId())
                : null;
        return new AiConversationPage(conversations, nextCursor, hasMore);
    }

    @Override
    @Transactional(readOnly = true)
    public AiConversationHistoryPage messages(
            long userId,
            byte[] conversationId,
            String beforeMessagePublicId,
            int pageSize) {
        requirePageSize(pageSize, MAX_MESSAGE_PAGE_SIZE);
        AiConversation conversation = conversationMapper.findActiveOwned(
                conversationId,
                userId);
        if (conversation == null) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_CONVERSATION_NOT_FOUND,
                    "AI conversation was not found.",
                    false);
        }
        Long beforeMessageId = decodeBefore(beforeMessagePublicId);
        List<AiConversationMessageHistoryRow> rows = messageMapper.findOwnedHistoryPage(
                conversationId,
                userId,
                beforeMessageId,
                pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<AiConversationMessageHistoryRow> selected = new ArrayList<>(
                hasMore ? rows.subList(0, pageSize) : rows);
        String nextBefore = hasMore && !selected.isEmpty()
                ? longPublicIds.encode(selected.get(selected.size() - 1).getMessageId())
                : null;
        // SQL 使用倒序游标保证稳定分页，返回前恢复正序，前端可直接追加到时间线顶部。
        Collections.reverse(selected);
        return new AiConversationHistoryPage(
                selected.stream().map(this::historyMessage).toList(),
                nextBefore,
                hasMore);
    }

    private AiConversationSummary summary(AiConversationSidebarRow conversation) {
        return new AiConversationSummary(
                hybridPublicIds.encode(conversation.getId()),
                conversation.getTitle(),
                longPublicIds.encode(conversation.getLastMessageId()),
                conversation.getCreatedAt());
    }

    private AiConversationHistoryMessage historyMessage(
            AiConversationMessageHistoryRow row) {
        return new AiConversationHistoryMessage(
                longPublicIds.encode(row.getMessageId()),
                row.getContentText(),
                attachments(row.getContentAttachmentsJson()),
                row.getQuestionTokens(),
                attachments(row.getResponseAttachmentsJson()),
                row.getCreatedAt(),
                row.getUsageId() == null ? null : hybridPublicIds.encode(row.getUsageId()),
                row.getAiModelId() == null ? null : longPublicIds.encode(row.getAiModelId()),
                row.getModelName(),
                decimal(row.getPromptTokens()),
                decimal(row.getCachedPromptTokens()),
                decimal(row.getCompletionTokens()),
                decimal(row.getReasoningTokens()),
                decimal(row.getChargedQuotaMinor()),
                row.getFinishReason());
    }

    private List<AiConversationAttachment> attachments(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(objectMapper.readValue(json, ATTACHMENT_LIST));
        } catch (JsonProcessingException exception) {
            // 数据库中的附件快照是服务端生成的数据；损坏时不能静默隐藏，否则会形成不完整审计记录。
            throw new IllegalStateException("Persisted AI attachment JSON is invalid", exception);
        }
    }

    private AiConversationCursorCodec.Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return cursorCodec.decode(cursor);
        } catch (IllegalArgumentException exception) {
            throw invalidPagination("Conversation cursor is invalid.");
        }
    }

    private Long decodeBefore(String before) {
        if (before == null || before.isBlank()) {
            return null;
        }
        try {
            return longPublicIds.decode(before);
        } catch (IllegalArgumentException exception) {
            throw invalidPagination("Message cursor is invalid.");
        }
    }

    private static void requirePageSize(int pageSize, int maximum) {
        if (pageSize < 1 || pageSize > maximum) {
            throw invalidPagination("Page size is outside the allowed range.");
        }
    }

    private static String decimal(Long value) {
        return value == null ? null : Long.toString(value);
    }

    private static AiConversationException invalidPagination(String message) {
        return new AiConversationException(
                AiConversationErrorCode.AI_REQUEST_INVALID,
                message,
                false);
    }
}
