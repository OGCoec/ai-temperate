package com.example.temperate.functions.video;

import com.example.temperate.functions.video.dto.FcSignedVideoRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 在 FC 原生同步事件和 HTTP Trigger 事件信封之间恢复签名正文，并按调用类型编码小型 JSON 响应。
 */
public final class FcInvocationCodec {

    private static final int MAXIMUM_SIGNED_BODY_BYTES = 128 * 1024;
    private final ObjectMapper objectMapper;

    public FcInvocationCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public DecodedInvocation decode(byte[] eventBytes) throws IOException {
        JsonNode root = objectMapper.readTree(eventBytes);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("FC invocation event is invalid.");
        }
        boolean hasHttpEnvelope = root.has("body") || root.has("requestContext");
        JsonNode signedRoot = root;
        if (hasHttpEnvelope) {
            String method = root.path("requestContext")
                    .path("http")
                    .path("method")
                    .asText("");
            JsonNode body = root.path("body");
            if (!"POST".equalsIgnoreCase(method)
                    || root.path("isBase64Encoded").asBoolean(false)
                    || !body.isTextual()) {
                throw new IllegalArgumentException(
                        "FC HTTP trigger event is invalid.");
            }
            String signedBody = body.asText();
            if (signedBody.getBytes(StandardCharsets.UTF_8).length
                    > MAXIMUM_SIGNED_BODY_BYTES) {
                throw new IllegalArgumentException(
                        "FC signed request body is too large.");
            }
            signedRoot = objectMapper.readTree(signedBody);
        }
        FcSignedVideoRequest request = objectMapper.treeToValue(
                signedRoot, FcSignedVideoRequest.class);
        return new DecodedInvocation(request, hasHttpEnvelope);
    }

    public void writeResponse(
            OutputStream output,
            Object response,
            boolean httpTrigger) throws IOException {
        if (!httpTrigger) {
            objectMapper.writeValue(output, response);
            return;
        }
        String responseBody = objectMapper.writeValueAsString(response);
        if (responseBody.getBytes(StandardCharsets.UTF_8).length
                > MAXIMUM_SIGNED_BODY_BYTES) {
            throw new IOException("FC video response is too large.");
        }
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("statusCode", 200);
        envelope.putObject("headers")
                .put("Content-Type", "application/json; charset=utf-8");
        envelope.put("isBase64Encoded", false);
        envelope.put("body", responseBody);
        objectMapper.writeValue(output, envelope);
    }

    /**
     * 保存已恢复的签名请求及其调用类型，避免业务 Handler 再次猜测 FC 触发器格式。
     */
    public static final class DecodedInvocation {

        private final FcSignedVideoRequest request;
        private final boolean httpTrigger;

        private DecodedInvocation(
                FcSignedVideoRequest request,
                boolean httpTrigger) {
            this.request = Objects.requireNonNull(request);
            this.httpTrigger = httpTrigger;
        }

        public FcSignedVideoRequest request() {
            return request;
        }

        public boolean httpTrigger() {
            return httpTrigger;
        }
    }
}
