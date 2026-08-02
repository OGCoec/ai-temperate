package com.example.temperate.service.user.aiconversation.history.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.ai.AiConversationMapper;
import com.example.temperate.mapper.ai.AiConversationMessageMapper;
import com.example.temperate.model.ai.entity.AiConversation;
import com.example.temperate.model.ai.entity.AiConversationMessageHistoryRow;
import com.example.temperate.service.user.aiconversation.history.AiConversationCursorCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证历史服务只从 PostgreSQL 批量投影侧栏和完整消息，并保持稳定游标与时间顺序。
 */
final class AiConversationHistoryServiceImplTest {

    @Test
    void conversationPageUsesLimitPlusOneAndCompositeCursor() {
        AiConversationMapper conversations = mock(AiConversationMapper.class);
        AiConversationMessageMapper messages = mock(AiConversationMessageMapper.class);
        AiConversation first = conversation((byte) 1, 30L, "first");
        AiConversation second = conversation((byte) 2, 20L, null);
        AiConversation overflow = conversation((byte) 3, 10L, "overflow");
        when(conversations.findActivePage(7L, null, null, 3))
                .thenReturn(List.of(first, second, overflow));
        AiConversationHistoryServiceImpl service = service(conversations, messages);

        var page = service.list(7L, null, 2);

        assertThat(page.conversations()).hasSize(2);
        assertThat(page.conversations().get(1).title()).isNull();
        assertThat(page.nextCursor()).hasSize(32);
        assertThat(page.hasMore()).isTrue();
        verify(conversations).findActivePage(7L, null, null, 3);
    }

    @Test
    void messagePageReturnsChronologicalPostgresRowsWithFormalAttachments() {
        AiConversationMapper conversations = mock(AiConversationMapper.class);
        AiConversationMessageMapper messages = mock(AiConversationMessageMapper.class);
        byte[] conversationId = id((byte) 9);
        when(conversations.findActiveOwned(conversationId, 7L))
                .thenReturn(conversation((byte) 9, 2L, "history"));
        when(messages.findOwnedHistoryPage(conversationId, 7L, null, 3))
                .thenReturn(List.of(historyRow(2L), historyRow(1L)));
        AiConversationHistoryServiceImpl service = service(conversations, messages);

        var page = service.messages(7L, conversationId, null, 2);

        assertThat(page.messages())
                .extracting(message -> message.messagePublicId())
                .containsExactly("AAAAAAAAAAE", "AAAAAAAAAAI");
        assertThat(page.messages().get(0).contentAttachments())
                .singleElement()
                .satisfies(attachment -> assertThat(attachment.url())
                        .isEqualTo("https://public-oss.example.test/file.png"));
        assertThat(page.hasMore()).isFalse();
    }

    private static AiConversationHistoryServiceImpl service(
            AiConversationMapper conversations,
            AiConversationMessageMapper messages) {
        return new AiConversationHistoryServiceImpl(
                conversations,
                messages,
                new HybridBase64UrlCodec(),
                new PublicIdCodec(),
                new AiConversationCursorCodec(),
                new ObjectMapper());
    }

    private static AiConversation conversation(
            byte suffix,
            long lastMessageId,
            String title) {
        AiConversation value = new AiConversation();
        value.setId(id(suffix));
        value.setLoginIdentityId(7L);
        value.setActive(true);
        value.setTitle(title);
        value.setLastMessageId(lastMessageId);
        value.setCreatedAt(OffsetDateTime.parse("2026-07-30T12:00:00Z"));
        return value;
    }

    private static AiConversationMessageHistoryRow historyRow(long messageId) {
        AiConversationMessageHistoryRow row = new AiConversationMessageHistoryRow();
        row.setMessageId(messageId);
        row.setConversationId(id((byte) 9));
        row.setContentText("question " + messageId);
        row.setContentAttachmentsJson("[{\"schemaVersion\":1,"
                + "\"attachmentId\":\"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKL\","
                + "\"fileName\":\"file.png\",\"contentType\":\"image/png\","
                + "\"sizeBytes\":\"12\",\"category\":\"IMAGE\","
                + "\"url\":\"https://public-oss.example.test/file.png\","
                + "\"state\":\"AVAILABLE\",\"failureCode\":null}]");
        row.setQuestionTokens("answer " + messageId);
        row.setResponseAttachmentsJson("[]");
        row.setCreatedAt(OffsetDateTime.parse("2026-07-30T12:00:00Z"));
        row.setUsageId(id((byte) messageId));
        row.setAiModelId(3L);
        row.setModelName("gpt-test");
        row.setPromptTokens(10L);
        row.setCachedPromptTokens(2L);
        row.setCompletionTokens(20L);
        row.setReasoningTokens(4L);
        row.setChargedQuotaMinor(8L);
        row.setFinishReason("STOP");
        return row;
    }

    private static byte[] id(byte suffix) {
        byte[] value = new byte[16];
        value[15] = suffix;
        return value;
    }
}
