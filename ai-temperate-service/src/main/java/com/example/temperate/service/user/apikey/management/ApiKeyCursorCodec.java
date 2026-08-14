package com.example.temperate.service.user.apikey.management;

import java.nio.ByteBuffer;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

/**
 * 该工具是来把 API Key 列表的创建时间和主键组合成规范 Base64URL 游标，保证相同时间戳下仍按 ID 稳定翻页。
 */
public final class ApiKeyCursorCodec {

    private static final int BYTE_LENGTH = Long.BYTES + Integer.BYTES + Long.BYTES;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public String encode(OffsetDateTime createdAt, long id) {
        if (createdAt == null || id <= 0) {
            throw new IllegalArgumentException("API Key cursor values are invalid");
        }
        Instant instant = createdAt.toInstant();
        return ENCODER.encodeToString(ByteBuffer.allocate(BYTE_LENGTH)
                .putLong(instant.getEpochSecond())
                .putInt(instant.getNano())
                .putLong(id)
                .array());
    }

    public Cursor decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.contains("=")) {
            throw invalid();
        }
        try {
            byte[] decoded = DECODER.decode(encoded);
            if (decoded.length != BYTE_LENGTH
                    || !ENCODER.encodeToString(decoded).equals(encoded)) {
                throw invalid();
            }
            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            long seconds = buffer.getLong();
            int nanos = buffer.getInt();
            long id = buffer.getLong();
            if (nanos < 0 || nanos > 999_999_999 || id <= 0) {
                throw invalid();
            }
            return new Cursor(
                    OffsetDateTime.ofInstant(Instant.ofEpochSecond(seconds, nanos), ZoneOffset.UTC),
                    id);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw invalid();
        }
    }

    private static ApiKeyManagementException invalid() {
        return new ApiKeyManagementException(
                ApiKeyManagementErrorCode.CURSOR_INVALID,
                "API Key cursor is invalid");
    }

    /** 游标内部值只在 Service 与 Mapper 之间流转，不作为资源 ID 暴露。 */
    public record Cursor(OffsetDateTime createdAt, long id) {
    }
}
