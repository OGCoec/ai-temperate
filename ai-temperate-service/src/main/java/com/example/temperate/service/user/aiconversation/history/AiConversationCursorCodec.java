package com.example.temperate.service.user.aiconversation.history;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 把最后消息 ID 与 16 字节会话 ID 编码为不暴露数字主键的规范 Base64URL 复合游标。
 */
@Component
public final class AiConversationCursorCodec {

    private static final int BINARY_LENGTH = Long.BYTES + 16;
    private static final int ENCODED_LENGTH = 32;
    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]{32}$");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public String encode(long lastMessageId, byte[] conversationId) {
        if (lastMessageId <= 0L || conversationId == null || conversationId.length != 16) {
            throw new IllegalArgumentException("Conversation cursor values are invalid");
        }
        return ENCODER.encodeToString(ByteBuffer.allocate(BINARY_LENGTH)
                .putLong(lastMessageId)
                .put(conversationId)
                .array());
    }

    public Cursor decode(String value) {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Conversation cursor must be canonical Base64URL");
        }
        byte[] decoded;
        try {
            decoded = DECODER.decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Conversation cursor is invalid", exception);
        }
        if (decoded.length != BINARY_LENGTH) {
            throw new IllegalArgumentException("Conversation cursor has an invalid byte length");
        }
        ByteBuffer buffer = ByteBuffer.wrap(decoded);
        long lastMessageId = buffer.getLong();
        byte[] conversationId = Arrays.copyOfRange(decoded, Long.BYTES, decoded.length);
        if (lastMessageId <= 0L || !encode(lastMessageId, conversationId).equals(value)) {
            throw new IllegalArgumentException("Conversation cursor is not canonical");
        }
        return new Cursor(lastMessageId, conversationId);
    }

    public record Cursor(long lastMessageId, byte[] conversationId) {

        public Cursor {
            conversationId = conversationId.clone();
        }

        @Override
        public byte[] conversationId() {
            return conversationId.clone();
        }
    }
}
