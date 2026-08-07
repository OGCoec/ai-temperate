package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.diagnostic.AiUpstreamErrorDiagnostic;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

/**
 * 在固定内存上限内读取上游错误正文，并把允许记录的字段转换为脱敏诊断对象。
 *
 * <p>捕获器只识别有限错误信封，永远忽略供应商返回的 input、Prompt 和未知对象；任何读取或解析失败
 * 都降级为安全元数据，不能覆盖原始 HTTP 失败。</p>
 */
final class AiUpstreamErrorCapture {

    private static final int MAX_CAPTURE_BYTES = 16 * 1024;
    private static final int READ_LIMIT_BYTES = MAX_CAPTURE_BYTES + 1;
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final int MAX_MESSAGE_LENGTH = 512;
    private static final String UNAVAILABLE = AiUpstreamErrorDiagnostic.UNAVAILABLE;
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile(
            "^[A-Za-z0-9._:+/\\-\\[\\]]{1," + MAX_IDENTIFIER_LENGTH + "}$");
    private static final Pattern URL_WITH_QUERY = Pattern.compile(
            "(?i)https?://[^\\s\\\"']+\\?[^\\s\\\"']*");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern CREDENTIAL_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(?:api[_ -]?key|access[_ -]?token|refresh[_ -]?token|"
                    + "token|secret|password|authorization)\\b\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9._%+-])");
    private static final Pattern IPV4 = Pattern.compile(
            "(?<![0-9])(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})"
                    + "(?:\\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})){3}(?![0-9])");
    private static final Pattern IPV6 = Pattern.compile(
            "(?i)(?<![A-F0-9:])(?:[A-F0-9]{0,4}:){2,7}[A-F0-9]{0,4}(?![A-F0-9:])");
    private static final Pattern PHONE = Pattern.compile(
            "(?<![A-Za-z0-9])\\+?[0-9][0-9() .-]{6,}[0-9](?![A-Za-z0-9])");
    private static final Pattern HIGH_ENTROPY_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_-])(?=[A-Za-z0-9_-]{24,}(?![A-Za-z0-9_-]))"
                    + "(?=[A-Za-z0-9_-]*[A-Za-z])(?=[A-Za-z0-9_-]*[0-9])"
                    + "[A-Za-z0-9_-]{24,}(?![A-Za-z0-9_-])");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final List<String> REQUEST_ID_HEADERS = List.of(
            "x-request-id",
            "request-id",
            "x-grok-request-id",
            "cf-ray");

    private final ObjectMapper objectMapper;

    AiUpstreamErrorCapture(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    Mono<AiUpstreamErrorDiagnostic> capture(ClientResponse response) {
        Objects.requireNonNull(response);
        return Mono.defer(() -> {
                    String contentType = safeContentType(response);
                    String headerRequestId = safeHeaderRequestId(
                            response.headers().asHttpHeaders());
                    return DataBufferUtils.join(
                                    DataBufferUtils.takeUntilByteCount(
                                            response.bodyToFlux(DataBuffer.class),
                                            READ_LIMIT_BYTES),
                                    READ_LIMIT_BYTES)
                            .map(AiUpstreamErrorCapture::readCapturedBody)
                            .defaultIfEmpty(CapturedBody.empty())
                            .map(body -> diagnostic(
                                    body,
                                    contentType,
                                    headerRequestId))
                            .onErrorReturn(metadataOnly(
                                    contentType,
                                    headerRequestId));
                })
                // 即使响应头本身非法，也必须保留原始 HTTP 失败语义，不能让诊断代码成为新的根因。
                .onErrorReturn(AiUpstreamErrorDiagnostic.unavailable());
    }

    private AiUpstreamErrorDiagnostic diagnostic(
            CapturedBody body,
            String contentType,
            String headerRequestId) {
        byte[] bytes = body.bytes();
        String fingerprint = sha256(bytes);
        if (body.truncated() || bytes.length == 0) {
            return new AiUpstreamErrorDiagnostic(
                    UNAVAILABLE,
                    UNAVAILABLE,
                    UNAVAILABLE,
                    UNAVAILABLE,
                    headerRequestId,
                    contentType,
                    fingerprint,
                    bytes.length,
                    body.truncated());
        }
        try {
            JsonNode root = objectMapper.readTree(bytes);
            if (root == null || !root.isContainerNode()) {
                return metadataOnly(
                        contentType,
                        headerRequestId,
                        fingerprint,
                        bytes.length,
                        false);
            }
            ExtractedError extracted = extract(root, headerRequestId);
            extracted = unwrapSerializedMessage(extracted, headerRequestId);
            return new AiUpstreamErrorDiagnostic(
                    safeIdentifier(extracted.providerCode()),
                    safeIdentifier(extracted.providerType()),
                    safeIdentifier(extracted.providerParam()),
                    sanitizeMessage(extracted.message()),
                    safeIdentifier(extracted.requestId()),
                    contentType,
                    fingerprint,
                    bytes.length,
                    false);
        } catch (RuntimeException | java.io.IOException ignored) {
            return metadataOnly(
                    contentType,
                    headerRequestId,
                    fingerprint,
                    bytes.length,
                    false);
        }
    }

    private static CapturedBody readCapturedBody(DataBuffer buffer) {
        try {
            byte[] received = new byte[buffer.readableByteCount()];
            buffer.read(received);
            boolean truncated = received.length > MAX_CAPTURE_BYTES;
            byte[] captured = truncated
                    ? Arrays.copyOf(received, MAX_CAPTURE_BYTES)
                    : received;
            return new CapturedBody(captured, truncated);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private static ExtractedError extract(
            JsonNode root,
            String headerRequestId) {
        JsonNode error = objectOrMissing(root.path("error"));
        String providerCode = firstValue(
                error.path("code"), root.path("code"));
        String providerType = firstValue(
                error.path("type"), root.path("type"));
        String providerParam = firstValue(
                error.path("param"), root.path("param"));
        String message = firstValue(
                error.path("message"), root.path("message"));
        String requestId = firstValue(
                error.path("request_id"),
                error.path("requestId"),
                root.path("request_id"),
                root.path("requestId"));

        JsonNode detail = root.path("detail");
        if (detail.isTextual()) {
            message = firstNonBlank(message, detail.asText());
        } else {
            JsonNode item = detail.isArray() && !detail.isEmpty()
                    ? detail.path(0)
                    : detail;
            if (item.isObject()) {
                providerType = firstNonBlank(
                        providerType, value(item.path("type")));
                providerParam = firstNonBlank(
                        providerParam, location(item.path("loc")));
                message = firstNonBlank(
                        message,
                        firstValue(item.path("msg"), item.path("message")));
            }
        }
        return new ExtractedError(
                providerCode,
                providerType,
                providerParam,
                message,
                firstNonBlank(requestId, headerRequestId));
    }

    private static JsonNode objectOrMissing(JsonNode node) {
        return node.isObject() ? node : MissingNode.getInstance();
    }

    private ExtractedError unwrapSerializedMessage(
            ExtractedError outer,
            String headerRequestId) {
        String message = outer.message();
        if (message == null || message.isBlank()) {
            return outer;
        }
        String trimmed = message.trim();
        String serializedJson = serializedJsonCandidate(trimmed);
        if (serializedJson == null) {
            return outer;
        }
        try {
            JsonNode nestedRoot = objectMapper.readTree(serializedJson);
            if (nestedRoot == null || !nestedRoot.isContainerNode()) {
                return withoutSerializedMessage(outer);
            }
            ExtractedError nested = extract(nestedRoot, headerRequestId);
            return new ExtractedError(
                    firstNonBlank(nested.providerCode(), outer.providerCode()),
                    firstNonBlank(nested.providerType(), outer.providerType()),
                    firstNonBlank(nested.providerParam(), outer.providerParam()),
                    nested.message(),
                    firstNonBlank(nested.requestId(), outer.requestId()));
        } catch (RuntimeException | java.io.IOException ignored) {
            // 无法证明嵌套 JSON 安全时必须丢弃整段消息，避免供应商把请求 input 原样带入日志。
            return withoutSerializedMessage(outer);
        }
    }

    private static String serializedJsonCandidate(String message) {
        int objectStart = message.indexOf('{');
        if (objectStart >= 0) {
            int objectEnd = message.lastIndexOf('}');
            return objectEnd > objectStart
                    ? message.substring(objectStart, objectEnd + 1)
                    : "";
        }
        int arrayStart = message.indexOf('[');
        if (arrayStart >= 0) {
            int arrayEnd = message.lastIndexOf(']');
            return arrayEnd > arrayStart
                    ? message.substring(arrayStart, arrayEnd + 1)
                    : "";
        }
        return null;
    }

    private static ExtractedError withoutSerializedMessage(
            ExtractedError outer) {
        return new ExtractedError(
                outer.providerCode(),
                outer.providerType(),
                outer.providerParam(),
                null,
                outer.requestId());
    }

    private static String firstValue(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            String value = value(node);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String value(JsonNode node) {
        return node != null && node.isValueNode() && !node.isNull()
                ? node.asText()
                : null;
    }

    private static String location(JsonNode location) {
        if (!location.isArray() || location.isEmpty()) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        for (JsonNode segment : location) {
            String value = value(segment);
            if (value == null || value.isBlank()) {
                return null;
            }
            segments.add(value);
        }
        return String.join(".", segments);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static String safeIdentifier(String value) {
        if (value == null) {
            return UNAVAILABLE;
        }
        String trimmed = value.trim();
        return trimmed.length() <= MAX_IDENTIFIER_LENGTH
                        && SAFE_IDENTIFIER.matcher(trimmed).matches()
                ? trimmed
                : UNAVAILABLE;
    }

    private static String sanitizeMessage(String value) {
        if (value == null || value.isBlank()) {
            return UNAVAILABLE;
        }
        String sanitized = removeLogControls(value);
        sanitized = URL_WITH_QUERY.matcher(sanitized)
                .replaceAll("<redacted-url>");
        sanitized = BEARER.matcher(sanitized)
                .replaceAll("Bearer <redacted-credential>");
        sanitized = CREDENTIAL_ASSIGNMENT.matcher(sanitized)
                .replaceAll("<redacted-credential>");
        sanitized = EMAIL.matcher(sanitized)
                .replaceAll("<redacted-email>");
        sanitized = IPV4.matcher(sanitized)
                .replaceAll("<redacted-ip>");
        sanitized = IPV6.matcher(sanitized)
                .replaceAll("<redacted-ip>");
        sanitized = PHONE.matcher(sanitized)
                .replaceAll("<redacted-phone>");
        sanitized = HIGH_ENTROPY_TOKEN.matcher(sanitized)
                .replaceAll("<redacted-token>");
        sanitized = WHITESPACE.matcher(sanitized).replaceAll(" ").trim();
        if (sanitized.isBlank()) {
            return UNAVAILABLE;
        }
        return escapeAndBoundMessage(sanitized);
    }

    private static String escapeAndBoundMessage(String value) {
        StringBuilder escaped = new StringBuilder(
                Math.min(value.length(), MAX_MESSAGE_LENGTH));
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String next = switch (codePoint) {
                case '\\' -> "\\\\";
                case '"' -> "\\\"";
                default -> new String(Character.toChars(codePoint));
            };
            if (escaped.length() + next.length() > MAX_MESSAGE_LENGTH) {
                break;
            }
            escaped.append(next);
            offset += Character.charCount(codePoint);
        }
        return escaped.toString();
    }

    private static String removeLogControls(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            result.append(Character.isISOControl(current)
                            || current == '\u2028'
                            || current == '\u2029'
                    ? ' '
                    : current);
        }
        return result.toString();
    }

    private static String safeContentType(ClientResponse response) {
        try {
            return response.headers().contentType()
                    .map(AiUpstreamErrorCapture::baseContentType)
                    .map(AiUpstreamErrorCapture::safeIdentifier)
                    .orElse(UNAVAILABLE);
        } catch (RuntimeException ignored) {
            return UNAVAILABLE;
        }
    }

    private static String baseContentType(MediaType mediaType) {
        return (mediaType.getType() + "/" + mediaType.getSubtype())
                .toLowerCase(Locale.ROOT);
    }

    private static String safeHeaderRequestId(HttpHeaders headers) {
        for (String name : REQUEST_ID_HEADERS) {
            String value;
            try {
                value = safeIdentifier(headers.getFirst(name));
            } catch (RuntimeException ignored) {
                value = UNAVAILABLE;
            }
            if (!UNAVAILABLE.equals(value)) {
                return value;
            }
        }
        return UNAVAILABLE;
    }

    private static String sha256(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static AiUpstreamErrorDiagnostic metadataOnly(
            String contentType,
            String requestId) {
        return metadataOnly(
                contentType,
                requestId,
                UNAVAILABLE,
                0,
                false);
    }

    private static AiUpstreamErrorDiagnostic metadataOnly(
            String contentType,
            String requestId,
            String bodySha256,
            int capturedBytes,
            boolean truncated) {
        return new AiUpstreamErrorDiagnostic(
                UNAVAILABLE,
                UNAVAILABLE,
                UNAVAILABLE,
                UNAVAILABLE,
                requestId,
                contentType,
                bodySha256,
                capturedBytes,
                truncated);
    }

    private record CapturedBody(byte[] bytes, boolean truncated) {

        private CapturedBody {
            bytes = Arrays.copyOf(bytes, bytes.length);
        }

        private static CapturedBody empty() {
            return new CapturedBody(new byte[0], false);
        }

        @Override
        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }
    }

    private record ExtractedError(
            String providerCode,
            String providerType,
            String providerParam,
            String message,
            String requestId) {
    }
}
