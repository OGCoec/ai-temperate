package com.example.temperate.service.user.apikey.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.apikey.management.ApiKeyManagementErrorCode;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来保证 API Key 调用记录游标规范编码时间和主键，并拒绝别名、填充符与无效主键。
 */
final class ApiKeyUsageCursorCodecTest {

    private final ApiKeyUsageCursorCodec codec = new ApiKeyUsageCursorCodec();

    @Test
    void roundTripsUtcTimestampAndId() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-18T15:42:10.123456789Z");
        byte[] usageId = new byte[16];
        Arrays.fill(usageId, (byte) 27);

        String encoded = codec.encode(createdAt, usageId);
        ApiKeyUsageCursorCodec.Cursor decoded = codec.decode(encoded);

        assertThat(decoded.createdAt()).isEqualTo(createdAt);
        assertThat(decoded.usageId()).containsExactly(usageId);
        assertThat(encoded).hasSize(38);
    }

    @Test
    void rejectsNonCanonicalCursor() {
        assertThatThrownBy(() -> codec.decode("bad="))
                .isInstanceOf(ApiKeyManagementException.class)
                .extracting(error -> ((ApiKeyManagementException) error).code())
                .isEqualTo(ApiKeyManagementErrorCode.CURSOR_INVALID);
        assertThatThrownBy(() -> codec.encode(
                OffsetDateTime.parse("2026-08-18T15:42:10Z"),
                new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
