package com.example.temperate.service.user.aiconversation.diagnostic.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证多路图片诊断只保留十个有界序号与请求标识，不允许任意字段进入结构化日志。
 */
final class AiConversationStreamTransportDiagnosticServiceImplTest {

    @Test
    void sanitizesAndBoundsImageSubrequestDiagnostics() {
        List<Object> values = new ArrayList<>();
        values.add(Map.of(
                "outputIndex", -1,
                "upstreamRequestId", "ignored"));
        values.add(Map.of(
                "outputIndex", 0,
                "requestIdPresent", true,
                "prompt", "must-not-survive"));
        for (int index = 1; index < 12; index++) {
            values.add(Map.of(
                    "outputIndex", index % 10,
                    "upstreamRequestId", "r".repeat(160)));
        }

        List<Map<String, Object>> safe =
                AiConversationStreamTransportDiagnosticServiceImpl
                        .safeSubrequests(values);

        assertThat(safe).hasSize(10);
        assertThat(safe.get(0))
                .containsEntry("outputIndex", 0)
                .containsEntry("requestIdPresent", true)
                .doesNotContainKey("upstreamRequestId")
                .doesNotContainKey("prompt");
        assertThat(safe.get(1))
                .containsEntry("requestIdPresent", true)
                .doesNotContainKey("upstreamRequestId");
    }

    @Test
    void sanitizesImageCheckpointFieldsWithoutAllowingPayloads() {
        Map<String, Object> safe =
                AiConversationStreamTransportDiagnosticServiceImpl.safeDetails(Map.ofEntries(
                        Map.entry("checkpoint", "P3_EVENT_MAPPED"),
                        Map.entry("outputIndex", 2),
                        Map.entry("partialImageIndex", 1),
                        Map.entry("upstreamJsonType", "image_generation.partial_image"),
                        Map.entry("mappingOutcome", "PARTIAL"),
                        Map.entry("encodedImageCharacters", 4096),
                        Map.entry("prompt", "must-not-survive"),
                        Map.entry("base64", "must-not-survive"),
                        Map.entry("signedUrl", "must-not-survive")));

        assertThat(safe)
                .containsEntry("checkpoint", "P3_EVENT_MAPPED")
                .containsEntry("outputIndex", 2)
                .containsEntry("partialImageIndex", 1)
                .containsEntry("upstreamJsonType", "image_generation.partial_image")
                .containsEntry("mappingOutcome", "PARTIAL")
                .containsEntry("encodedImageCharacters", 4096)
                .doesNotContainKeys("prompt", "base64", "signedUrl");
    }

    @Test
    void dropsOutOfRangeIndexesAndNormalizesUnsafeProtocolStrings() {
        Map<String, Object> safe =
                AiConversationStreamTransportDiagnosticServiceImpl.safeDetails(Map.of(
                        "outputIndex", 10,
                        "partialImageIndex", 3,
                        "upstreamEventName", "eyJhbGciOiJIUzI1NiJ9.secret.signature",
                        "upstreamJsonType", "response.image_generation_call.partial_image",
                        "responseContentType", "text/event-stream; token=secret",
                        "requestPath", "https://signed.example/image?token=secret",
                        "eventCharacters", -1));

        assertThat(safe)
                .doesNotContainKeys(
                        "outputIndex",
                        "partialImageIndex",
                        "eventCharacters")
                .containsEntry("upstreamEventName", "unknown")
                .containsEntry(
                        "upstreamJsonType",
                        "response.image_generation_call.partial_image")
                .containsEntry("responseContentType", "other")
                .containsEntry("requestPath", "custom");
    }
}
