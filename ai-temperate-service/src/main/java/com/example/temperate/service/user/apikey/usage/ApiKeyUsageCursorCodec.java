package com.example.temperate.service.user.apikey.usage;

import com.example.temperate.service.user.apikey.management.ApiKeyManagementErrorCode;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementException;
import java.nio.ByteBuffer;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

/**
 * 该编解码器是来把调用时间与用量主键组成规范 Base64URL 游标，使相同时间戳的明细仍能稳定倒序翻页。
 */
public final class ApiKeyUsageCursorCodec {

    private static final int ID_LENGTH = 16;
    private static final int BYTE_LENGTH = Long.BYTES + Integer.BYTES + ID_LENGTH;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public String encode(OffsetDateTime createdAt, byte[] usageId) {
        if (createdAt == null
                || usageId == null
                || usageId.length != ID_LENGTH
                || isZero(usageId)) {
            throw new IllegalArgumentException("API Key usage cursor values are invalid");
        }
        Instant instant = createdAt.toInstant();
        return ENCODER.encodeToString(ByteBuffer.allocate(BYTE_LENGTH)
                .putLong(instant.getEpochSecond())
                .putInt(instant.getNano())
                .put(usageId)
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
            byte[] usageId = new byte[ID_LENGTH];
            buffer.get(usageId);
            if (nanos < 0 || nanos > 999_999_999 || isZero(usageId)) {
                throw invalid();
            }
            return new Cursor(
                    OffsetDateTime.ofInstant(
                            Instant.ofEpochSecond(seconds, nanos),
                            ZoneOffset.UTC),
                    usageId);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw invalid();
        }
    }

    private static boolean isZero(byte[] value) {
        int aggregate = 0;
        for (byte current : value) {
            aggregate |= current;
        }
        return aggregate == 0;
    }

    private static ApiKeyManagementException invalid() {
        return new ApiKeyManagementException(
                ApiKeyManagementErrorCode.CURSOR_INVALID,
                "API Key usage cursor is invalid");
    }

    /** 游标内部值只供 Service 与 Mapper 使用，响应不会暴露用量主键。 */
    public record Cursor(OffsetDateTime createdAt, byte[] usageId) {

        public Cursor {
            usageId = usageId.clone();
        }

        @Override
        public byte[] usageId() {
            return usageId.clone();
        }
    }
}
