package com.example.temperate.functions.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.temperate.functions.video.dto.FcSignedVideoRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证 FC 原生同步事件与 HTTP Trigger 信封都能恢复同一签名正文，并生成对应的小型响应格式。
 */
final class FcInvocationCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FcInvocationCodec codec = new FcInvocationCodec(objectMapper);

    @Test
    void decodesHttpTriggerBodyAndWrapsHttpResponse() throws Exception {
        String signedBody = "{\"timestamp\":\"1\",\"nonce\":\"nonce\","
                + "\"signature\":\"signature\",\"request\":{"
                + "\"operation\":\"probe\",\"payload\":{}}}";
        byte[] event = objectMapper.writeValueAsBytes(Map.of(
                "version", "v1",
                "body", signedBody,
                "isBase64Encoded", false,
                "requestContext", Map.of(
                        "http", Map.of("method", "POST"))));

        FcInvocationCodec.DecodedInvocation decoded = codec.decode(event);

        assertTrue(decoded.httpTrigger());
        assertEquals("1", decoded.request().timestamp());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        codec.writeResponse(output, Map.of("objectKey", "ai/video/a.mp4"), true);
        JsonNode response = objectMapper.readTree(output.toByteArray());
        assertEquals(200, response.path("statusCode").asInt());
        assertEquals("ai/video/a.mp4", objectMapper.readTree(
                response.path("body").asText()).path("objectKey").asText());
    }

    @Test
    void keepsNativeSynchronousInvocationAsRawJson() throws Exception {
        byte[] event = ("{\"timestamp\":\"1\",\"nonce\":\"nonce\","
                + "\"signature\":\"signature\",\"request\":{"
                + "\"operation\":\"probe\",\"payload\":{}}}")
                .getBytes(StandardCharsets.UTF_8);

        FcInvocationCodec.DecodedInvocation decoded = codec.decode(event);

        assertFalse(decoded.httpTrigger());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        codec.writeResponse(output, Map.of("ok", true), false);
        assertTrue(objectMapper.readTree(output.toByteArray()).path("ok").asBoolean());
    }

    @Test
    void rejectsBase64OrNonPostHttpTriggerEvents() throws Exception {
        for (Object invalid : new Object[] {
                Map.of(
                        "body", "{}",
                        "isBase64Encoded", true,
                        "requestContext", Map.of(
                                "http", Map.of("method", "POST"))),
                Map.of(
                        "body", "{}",
                        "isBase64Encoded", false,
                        "requestContext", Map.of(
                                "http", Map.of("method", "GET")))
        }) {
            assertThrows(IllegalArgumentException.class, () ->
                    codec.decode(objectMapper.writeValueAsBytes(invalid)));
        }
    }
}
