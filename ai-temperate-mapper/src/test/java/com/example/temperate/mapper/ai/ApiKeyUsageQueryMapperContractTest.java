package com.example.temperate.mapper.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 该契约测试是来锁定 API Key 调用记录只按摘要与半开时间区间查询，并通过单次连接返回模型和预扣详情。
 */
final class ApiKeyUsageQueryMapperContractTest {

    @Test
    void usesExistingKeyTimeIndexShapeAndStableCursorWithoutSensitiveProjection()
            throws IOException {
        String xml = readXml();

        assertThat(xml)
                .contains("usage.key_digest = #{keyDigest,jdbcType=BINARY}")
                .contains("usage.created_at &gt;= #{from,jdbcType=TIMESTAMP_WITH_TIMEZONE}")
                .contains("usage.created_at &lt; #{to,jdbcType=TIMESTAMP_WITH_TIMEZONE}")
                .contains("(usage.created_at, usage.id) &lt;")
                .contains("#{cursorId,jdbcType=BINARY}")
                .contains("ORDER BY usage.created_at DESC, usage.id DESC")
                .contains("LEFT JOIN ai_model_api_usage_detail detail")
                .contains("LEFT JOIN ai_model model")
                .doesNotContain("request_body")
                .doesNotContain("response_body")
                .doesNotContain("key_hint")
                .doesNotContain("api_key_id");
    }

    @Test
    void summaryKeepsOrphanCoreRowsAndSeparatesPendingReservations()
            throws IOException {
        String xml = readXml();

        assertThat(xml)
                .contains("COUNT(*) AS request_count")
                .contains("billing_status = 0")
                .contains("pending_request_count")
                .contains("pending_reserved_quota_minor")
                .contains("charged_quota_minor");
    }

    private static String readXml() throws IOException {
        try (InputStream stream = ApiKeyUsageQueryMapperContractTest.class
                .getClassLoader()
                .getResourceAsStream("mapper/ai/ApiKeyUsageQueryMapper.xml")) {
            if (stream == null) {
                throw new IOException("ApiKeyUsageQueryMapper.xml was not found");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
