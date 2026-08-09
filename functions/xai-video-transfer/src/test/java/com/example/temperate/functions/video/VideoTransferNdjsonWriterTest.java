package com.example.temperate.functions.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 验证 FC 向主服务输出的上传进度逐帧可解析，且不会泄漏 xAI 临时地址或凭据。
 */
final class VideoTransferNdjsonWriterTest {

    @Test
    void writesKnownAndUnknownLengthProgressAsIndependentNdjsonFrames()
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        VideoTransferNdjsonWriter writer = new VideoTransferNdjsonWriter(
                output, new ObjectMapper());

        writer.progress(1, 2_000_000L, 4_000_000L);
        writer.progress(2, 3_000_000L, null);

        String[] lines = output.toString(StandardCharsets.UTF_8).trim().split("\\r?\\n");
        assertEquals(2, lines.length);
        JsonNode known = new ObjectMapper().readTree(lines[0]);
        JsonNode unknown = new ObjectMapper().readTree(lines[1]);
        assertEquals("progress", known.path("type").asText());
        assertEquals(50, known.path("percent").asInt());
        assertEquals("progress", unknown.path("type").asText());
        assertFalse(unknown.hasNonNull("percent"));
        String serialized = output.toString(StandardCharsets.UTF_8);
        assertFalse(serialized.contains("sourceUrl"));
        assertFalse(serialized.contains("secret"));
    }

    @Test
    void sanitizesUnknownFailureTextToGenericStageCode() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        VideoTransferNdjsonWriter writer = new VideoTransferNdjsonWriter(
                output, new ObjectMapper());

        writer.failed(1, "https://source.example/video?signature=secret");

        JsonNode frame = new ObjectMapper().readTree(
                output.toString(StandardCharsets.UTF_8).trim());
        assertEquals("failed", frame.path("type").asText());
        assertEquals("OSS_TRANSFER_FAILED", frame.path("errorCode").asText());
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("secret"));
    }
}
