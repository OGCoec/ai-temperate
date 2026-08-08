package com.example.temperate.web.user.voice;

import com.example.temperate.service.user.voice.VoiceErrorCode;
import com.example.temperate.service.user.voice.VoiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 解析公开 WSS 的首个 session.start 控制帧并在转发前剥离一次性票据。
 *
 * <p>字段使用严格白名单，避免客户端把票据或未定义配置透传给本机 Whisper 服务。</p>
 */
public record VoiceWebSocketStartMessage(
        String ticket,
        String language,
        String format,
        int sampleRate,
        int channels) {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "type",
            "protocolVersion",
            "ticket",
            "language",
            "format",
            "sampleRate",
            "channels");
    private static final Pattern TICKET = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern LANGUAGE = Pattern.compile(
            "^(?:auto|[a-z]{2,3}(?:-[a-z]{2})?)$",
            Pattern.CASE_INSENSITIVE);

    public static VoiceWebSocketStartMessage parse(
            ObjectMapper objectMapper,
            String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root == null || !root.isObject()
                    || !"session.start".equals(text(root, "type"))
                    || !root.path("protocolVersion").isIntegralNumber()
                    || root.path("protocolVersion").asInt(-1) != 1
                    || !allFieldsAllowed(root)) {
                throw invalid();
            }
            String ticket = text(root, "ticket");
            String language = text(root, "language");
            String format = text(root, "format");
            int sampleRate = root.path("sampleRate").asInt(-1);
            int channels = root.path("channels").asInt(-1);
            if (!canonicalTicket(ticket)
                    || language == null || !LANGUAGE.matcher(language).matches()
                    || !"pcm_s16le".equals(format)
                    || !root.path("sampleRate").isIntegralNumber()
                    || !root.path("channels").isIntegralNumber()
                    || sampleRate != 16000
                    || channels != 1) {
                throw invalid();
            }
            return new VoiceWebSocketStartMessage(
                    ticket,
                    language.toLowerCase(java.util.Locale.ROOT),
                    format,
                    sampleRate,
                    channels);
        } catch (JsonProcessingException exception) {
            throw invalid();
        }
    }

    public String upstreamJson(ObjectMapper objectMapper) {
        Map<String, Object> upstream = new LinkedHashMap<>();
        upstream.put("type", "session.start");
        upstream.put("language", language);
        upstream.put("format", format);
        upstream.put("sampleRate", sampleRate);
        upstream.put("channels", channels);
        try {
            return objectMapper.writeValueAsString(upstream);
        } catch (JsonProcessingException exception) {
            throw new VoiceException(
                    VoiceErrorCode.VOICE_PROTOCOL_INVALID,
                    "语音连接协议无法序列化。",
                    false,
                    exception);
        }
    }

    @Override
    public String toString() {
        return "VoiceWebSocketStartMessage[ticket=redacted, language=" + language
                + ", format=" + format + ", sampleRate=" + sampleRate
                + ", channels=" + channels + "]";
    }

    private static boolean allFieldsAllowed(JsonNode root) {
        java.util.Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            if (!ALLOWED_FIELDS.contains(names.next())) {
                return false;
            }
        }
        return true;
    }

    private static boolean canonicalTicket(String value) {
        if (value == null || !TICKET.matcher(value).matches()) {
            return false;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            return decoded.length == 32
                    && Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(decoded).equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static VoiceException invalid() {
        return new VoiceException(
                VoiceErrorCode.VOICE_PROTOCOL_INVALID,
                "语音连接首帧格式不正确。",
                false);
    }
}
