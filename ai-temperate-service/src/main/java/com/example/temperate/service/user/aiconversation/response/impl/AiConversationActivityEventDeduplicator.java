package com.example.temperate.service.user.aiconversation.response.impl;

import com.example.temperate.service.user.aiconversation.model.stream.AiConversationActivityPhase;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationActivityStatus;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 为一次模型响应生成稳定的活动事件标识，并在下游分配顺序号前过滤完全相同的上游活动。
 *
 * <p>状态和查询属于事件业务身份，因此 STARTED、IN_PROGRESS 以及不同查询都会保留；
 * 传输顺序和发生时间不进入摘要，避免同一活动被重放时获得新的身份。</p>
 */
final class AiConversationActivityEventDeduplicator {

    private static final int MAXIMUM_UNIQUE_EVENTS = 500;
    private static final String EVENT_ID_PREFIX = "act_v1_";
    private static final String HASH_ALGORITHM = "SHA-256";

    private final Set<String> acceptedEventIds = new HashSet<>();

    String accept(AiConversationModelEvent.Activity activity) {
        Objects.requireNonNull(activity);
        String eventId = eventId(
                activity.activityId(),
                activity.phase(),
                activity.status(),
                activity.query());
        if (acceptedEventIds.contains(eventId)
                || acceptedEventIds.size() >= MAXIMUM_UNIQUE_EVENTS) {
            return null;
        }
        acceptedEventIds.add(eventId);
        return eventId;
    }

    static String eventId(
            String activityId,
            AiConversationActivityPhase phase,
            AiConversationActivityStatus status,
            String query) {
        MessageDigest digest = sha256();
        // 每个字段都使用长度前缀，防止字段内容中的分隔符产生相同拼接结果。
        update(digest, "activity-v1");
        update(digest, Objects.requireNonNull(activityId));
        update(digest, Objects.requireNonNull(phase).name());
        update(digest, Objects.requireNonNull(status).name());
        updateNullable(digest, query);
        return EVENT_ID_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = Objects.requireNonNull(value)
                .getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(bytes.length)
                .array());
        digest.update(bytes);
    }

    private static void updateNullable(MessageDigest digest, String value) {
        if (value == null) {
            // -1 长度专门表示缺失值，避免把 null 与业务上的空字符串错误合并。
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(-1)
                    .array());
            return;
        }
        update(digest, value);
    }
}
