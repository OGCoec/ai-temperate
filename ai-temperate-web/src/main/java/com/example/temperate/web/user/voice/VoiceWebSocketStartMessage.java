package com.example.temperate.web.user.voice;

import com.example.temperate.service.user.voice.VoiceErrorCode;
import com.example.temperate.service.user.voice.VoiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 解析已完成握手授权的 Voice v2 session.start 控制帧，并生成上游语音会话初始化消息。
 *
 * <p>字段使用严格白名单，Ticket 已在返回 101 前消费，因此首帧出现 Ticket 或其他未定义字段必须拒绝，
 * 防止认证凭据透传给本机 Whisper 服务。</p>
 */
public record VoiceWebSocketStartMessage(
        String language,
        String format,
        int sampleRate,
        int channels) {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "type",
            "protocolVersion",
            "language",
            "format",
            "sampleRate",
            "channels");
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
                    || root.path("protocolVersion").asInt(-1) != 2
                    || !allFieldsAllowed(root)) {
                throw invalid();
            }
            String language = text(root, "language");
            String format = text(root, "format");
            int sampleRate = root.path("sampleRate").asInt(-1);
            int channels = root.path("channels").asInt(-1);
            if (language == null || !LANGUAGE.matcher(language).matches()
                    || !"pcm_s16le".equals(format)
                    || !root.path("sampleRate").isIntegralNumber()
                    || !root.path("channels").isIntegralNumber()
                    || sampleRate != 16000
                    || channels != 1) {
                throw invalid();
            }
            return new VoiceWebSocketStartMessage(
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
        return "VoiceWebSocketStartMessage[language=" + language
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
