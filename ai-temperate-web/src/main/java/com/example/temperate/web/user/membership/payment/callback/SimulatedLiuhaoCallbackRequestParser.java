package com.example.temperate.web.user.membership.payment.callback;

import com.example.temperate.service.user.membership.payment.callback.SimulatedLiuhaoCallbackCommand;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * 该解析器是来把 GET Query、POST 表单和 POST JSON 严格归一化为同一命令，并拒绝重复键、未知字段、混合参数源和非字符串 JSON 值。
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment.simulator",
        name = "enabled",
        havingValue = "true")
public final class SimulatedLiuhaoCallbackRequestParser {

    private static final Set<String> EXPECTED_FIELDS = Set.of(
            "pid",
            "trade_no",
            "out_trade_no",
            "api_trade_no",
            "type",
            "trade_status",
            "addtime",
            "endtime",
            "name",
            "money",
            "param",
            "buyer",
            "timestamp",
            "sign",
            "sign_type");

    private final ObjectMapper strictObjectMapper;
    private final int maximumBytes;

    public SimulatedLiuhaoCallbackRequestParser(
            ObjectMapper objectMapper,
            MembershipPaymentProperties properties) {
        this.strictObjectMapper = Objects.requireNonNull(objectMapper).copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.maximumBytes = Objects.requireNonNull(properties)
                .simulator()
                .requestMaxBytes();
    }

    public SimulatedLiuhaoCallbackCommand parse(HttpServletRequest request) {
        HttpServletRequest value = Objects.requireNonNull(request);
        Map<String, String> fields = switch (value.getMethod()) {
            case "GET" -> parseGet(value);
            case "POST" -> parsePost(value);
            default -> throw badRequest("Unsupported callback method.");
        };
        requireExactFields(fields);
        return command(fields);
    }

    private Map<String, String> parseGet(HttpServletRequest request) {
        if (request.getContentLengthLong() > 0L
                || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
            throw badRequest("GET callback body is forbidden.");
        }
        String query = request.getQueryString();
        byte[] bytes = query == null
                ? new byte[0]
                : query.getBytes(StandardCharsets.UTF_8);
        requireSize(bytes.length);
        return parseUrlEncoded(bytes);
    }

    private Map<String, String> parsePost(HttpServletRequest request) {
        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) {
            throw badRequest("POST callback query parameters are forbidden.");
        }
        MediaType contentType = contentType(request.getContentType());
        requireUtf8(contentType);
        byte[] body = readBody(request);
        if (MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(contentType)) {
            return parseUrlEncoded(body);
        }
        if (MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            return parseJson(body);
        }
        throw unsupportedMediaType();
    }

    private Map<String, String> parseUrlEncoded(byte[] body) {
        Map<String, String> fields = new LinkedHashMap<>();
        String text = new String(body, StandardCharsets.UTF_8);
        if (text.isEmpty()) {
            return fields;
        }
        for (String pair : text.split("&", -1)) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                throw badRequest("Callback form field is malformed.");
            }
            String name = decode(pair.substring(0, separator));
            String value = decode(pair.substring(separator + 1));
            if (!EXPECTED_FIELDS.contains(name) || fields.putIfAbsent(name, value) != null) {
                throw badRequest("Callback contains an unknown or duplicate field.");
            }
        }
        return fields;
    }

    private Map<String, String> parseJson(byte[] body) {
        JsonNode root;
        try {
            root = strictObjectMapper.reader().readTree(body);
        } catch (JsonProcessingException exception) {
            throw badRequest("Callback JSON is malformed.");
        } catch (IOException exception) {
            throw badRequest("Callback JSON cannot be read.");
        }
        if (root == null || !root.isObject()) {
            throw badRequest("Callback JSON must be an object.");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        root.fields().forEachRemaining(entry -> {
            if (!EXPECTED_FIELDS.contains(entry.getKey()) || !entry.getValue().isTextual()) {
                throw badRequest("Callback JSON fields must be known strings.");
            }
            fields.put(entry.getKey(), entry.getValue().textValue());
        });
        return fields;
    }

    private byte[] readBody(HttpServletRequest request) {
        long declared = request.getContentLengthLong();
        if (declared > maximumBytes) {
            throw badRequest("Callback body exceeds the configured limit.");
        }
        try {
            byte[] bytes = request.getInputStream().readNBytes(maximumBytes + 1);
            requireSize(bytes.length);
            return bytes;
        } catch (IOException exception) {
            throw badRequest("Callback body cannot be read.");
        }
    }

    private static MediaType contentType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw unsupportedMediaType();
        }
        try {
            return MediaType.parseMediaType(raw);
        } catch (InvalidMediaTypeException exception) {
            throw unsupportedMediaType();
        }
    }

    private static void requireUtf8(MediaType contentType) {
        if (contentType.getCharset() != null
                && !StandardCharsets.UTF_8.equals(contentType.getCharset())) {
            throw unsupportedMediaType();
        }
    }

    private void requireSize(int size) {
        if (size > maximumBytes) {
            throw badRequest("Callback payload exceeds the configured limit.");
        }
    }

    private static void requireExactFields(Map<String, String> fields) {
        if (!fields.keySet().equals(EXPECTED_FIELDS)) {
            throw badRequest("Callback fields are incomplete.");
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw badRequest("Callback form encoding is invalid.");
        }
    }

    private static SimulatedLiuhaoCallbackCommand command(Map<String, String> fields) {
        return new SimulatedLiuhaoCallbackCommand(
                fields.get("pid"),
                fields.get("trade_no"),
                fields.get("out_trade_no"),
                fields.get("api_trade_no"),
                fields.get("type"),
                fields.get("trade_status"),
                fields.get("addtime"),
                fields.get("endtime"),
                fields.get("name"),
                fields.get("money"),
                fields.get("param"),
                fields.get("buyer"),
                fields.get("timestamp"),
                fields.get("sign"),
                fields.get("sign_type"));
    }

    private static SimulatedPaymentCallbackTransportException badRequest(String message) {
        return new SimulatedPaymentCallbackTransportException(
                SimulatedPaymentCallbackTransportException.Kind.BAD_REQUEST,
                message);
    }

    private static SimulatedPaymentCallbackTransportException unsupportedMediaType() {
        return new SimulatedPaymentCallbackTransportException(
                SimulatedPaymentCallbackTransportException.Kind.UNSUPPORTED_MEDIA_TYPE,
                "Callback content type is unsupported.");
    }
}
