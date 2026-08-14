package com.example.temperate.service.user.apikey.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来约束 API Key 强 ETag 和稳定游标的规范编码，并确保任意畸形或越界输入都收敛为受控 400 类错误。
 */
final class ApiKeyManagementValueObjectsTest {

    @Test
    void strongVersionTagRoundTripsAndRejectsWeakOrMultipleValues() {
        assertThat(ApiKeyVersionTag.format(42)).isEqualTo("\"v42\"");
        assertThat(ApiKeyVersionTag.parseRequired("\"v42\"")).isEqualTo(42L);

        assertThatThrownBy(() -> ApiKeyVersionTag.parseRequired(null))
                .isInstanceOfSatisfying(ApiKeyManagementException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ApiKeyManagementErrorCode.VERSION_REQUIRED));
        assertThatThrownBy(() -> ApiKeyVersionTag.parseRequired("W/\"v42\""))
                .isInstanceOfSatisfying(ApiKeyManagementException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ApiKeyManagementErrorCode.VERSION_INVALID));
        assertThatThrownBy(() -> ApiKeyVersionTag.parseRequired("\"v1\", \"v2\""))
                .isInstanceOf(ApiKeyManagementException.class);
    }

    @Test
    void cursorRoundTripsTimestampAndId() {
        ApiKeyCursorCodec codec = new ApiKeyCursorCodec();
        OffsetDateTime createdAt = OffsetDateTime.of(
                2026, 8, 13, 12, 34, 56, 123_456_789, ZoneOffset.UTC);

        String encoded = codec.encode(createdAt, 99L);
        ApiKeyCursorCodec.Cursor decoded = codec.decode(encoded);

        assertThat(encoded).doesNotContain("=");
        assertThat(decoded.createdAt()).isEqualTo(createdAt);
        assertThat(decoded.id()).isEqualTo(99L);
    }

    @Test
    void cursorRejectsNonCanonicalAndOutOfRangeInstant() {
        ApiKeyCursorCodec codec = new ApiKeyCursorCodec();
        String impossibleInstant = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteBuffer.allocate(20)
                        .putLong(Long.MAX_VALUE)
                        .putInt(999_999_999)
                        .putLong(1L)
                        .array());

        assertThatThrownBy(() -> codec.decode("AAAA="))
                .isInstanceOf(ApiKeyManagementException.class);
        assertThatThrownBy(() -> codec.decode(impossibleInstant))
                .isInstanceOfSatisfying(ApiKeyManagementException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ApiKeyManagementErrorCode.CURSOR_INVALID));
    }
}
