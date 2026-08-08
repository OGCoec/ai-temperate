package com.example.temperate.service.user.aiconversation.video;

import com.example.temperate.service.user.aiconversation.config.AiConversationVideoGenerationProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 使用服务端配置前缀、UTC 日期和规范公共 ID 生成视频 OSS Key，阻断客户端路径注入与跨用户覆盖。
 */
@Component
public final class AiConversationVideoObjectKeyFactory {

    private final String prefix;
    private final Clock clock;

    public AiConversationVideoObjectKeyFactory(
            AiConversationVideoGenerationProperties properties,
            Clock clock) {
        String configured = Objects.requireNonNull(properties)
                .functionCompute()
                .objectPrefix();
        if (configured == null
                || configured.isBlank()
                || configured.startsWith("/")
                || configured.contains("..")
                || configured.contains("\\")) {
            throw new IllegalArgumentException("Video OSS object prefix is invalid.");
        }
        this.prefix = configured.endsWith("/") ? configured : configured + "/";
        this.clock = Objects.requireNonNull(clock);
    }

    public String create(
            String userPublicId,
            String conversationPublicId,
            String messagePublicId,
            String attachmentPublicId) {
        require(userPublicId, 11, "userPublicId");
        require(conversationPublicId, 22, "conversationPublicId");
        require(messagePublicId, 11, "messagePublicId");
        require(attachmentPublicId, 38, "attachmentPublicId");
        LocalDate date = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        return prefix
                + date.getYear() + "/"
                + String.format(java.util.Locale.ROOT, "%02d", date.getMonthValue()) + "/"
                + userPublicId + "/"
                + conversationPublicId + "/"
                + messagePublicId + "/"
                + attachmentPublicId + ".mp4";
    }

    private static void require(String value, int length, String field) {
        if (value == null
                || value.length() != length
                || !value.matches("^[A-Za-z0-9_-]+$")) {
            throw new IllegalArgumentException(field + " is invalid.");
        }
    }
}
