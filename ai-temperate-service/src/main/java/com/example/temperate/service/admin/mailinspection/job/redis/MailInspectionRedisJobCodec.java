package com.example.temperate.service.admin.mailinspection.job.redis;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionPendingItem;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 负责 Redis 邮件任务文档的稳定 JSON 编解码，并在边界处拒绝未知或损坏的 Schema。
 */
@Component
public final class MailInspectionRedisJobCodec {

    private final ObjectMapper objectMapper;

    public MailInspectionRedisJobCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public String writeJob(MailInspectionRedisJobDocument document) {
        return write(document);
    }

    public MailInspectionRedisJobDocument readJob(String json) {
        return read(json, MailInspectionRedisJobDocument.class);
    }

    public String writeResult(MailInspectionResult value) {
        return write(new MailInspectionRedisResultDocument(
                MailInspectionRedisResultDocument.SCHEMA_VERSION,
                value));
    }

    public MailInspectionResult readResult(String json) {
        return read(json, MailInspectionRedisResultDocument.class).result();
    }

    public String writePendingItems(List<MailInspectionPendingItem> values) {
        return write(new MailInspectionRedisPendingItemsDocument(
                MailInspectionRedisPendingItemsDocument.SCHEMA_VERSION,
                values == null ? List.of() : List.copyOf(values)));
    }

    public List<MailInspectionPendingItem> readPendingItems(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    json,
                    MailInspectionRedisPendingItemsDocument.class)
                    .items();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "mail inspection pending item document is invalid",
                    exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "mail inspection Redis document serialization failed",
                    exception);
        }
    }

    private <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException(
                    "mail inspection Redis document is missing");
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "mail inspection Redis document is invalid",
                    exception);
        }
    }
}
