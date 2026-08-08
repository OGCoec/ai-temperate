package com.example.temperate.service.user.aiconversation.model.stream.xai.video;

import com.example.temperate.service.user.aiconversation.video.AiConversationGeneratedVideo;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * 将 xAI 视频创建和轮询 JSON 转换为有界强类型结果，并拒绝非 HTTPS URL、负成本和未知状态。
 */
@Component
public final class XaiVideoResponseMapper {

    public XaiVideoStartResult mapStart(JsonNode root) {
        return new XaiVideoStartResult(requiredText(root, "request_id"));
    }

    public XaiVideoPollResult mapPoll(JsonNode root) {
        XaiVideoStatus status = XaiVideoStatus.fromUpstream(
                requiredText(root, "status"));
        int progress = root.path("progress").isIntegralNumber()
                ? root.path("progress").intValue()
                : status == XaiVideoStatus.DONE ? 100 : 0;
        Long costTicks = optionalNonNegativeLong(
                root.path("usage").path("cost_in_usd_ticks"));
        if (status != XaiVideoStatus.DONE) {
            return new XaiVideoPollResult(status, progress, null, costTicks);
        }
        JsonNode video = root.path("video");
        long durationMillis = durationMillis(video.path("duration"));
        return new XaiVideoPollResult(
                status,
                progress,
                new AiConversationGeneratedVideo(
                        "unavailable",
                        requiredText(video, "url"),
                        durationMillis,
                        optionalText(root, "model"),
                        video.path("respect_moderation").asBoolean(false)),
                costTicks);
    }

    public XaiVideoPollResult bindRequestId(
            XaiVideoPollResult result,
            String requestId) {
        if (result.video() == null) {
            return result;
        }
        AiConversationGeneratedVideo video = result.video();
        return new XaiVideoPollResult(
                result.status(),
                result.progress(),
                new AiConversationGeneratedVideo(
                        requestId,
                        video.ephemeralUrl(),
                        video.durationMillis(),
                        video.model(),
                        video.respectModeration()),
                result.costInUsdTicks());
    }

    private static long durationMillis(JsonNode node) {
        if (!node.isNumber()) {
            throw new IllegalArgumentException("xAI video duration is required.");
        }
        try {
            BigDecimal seconds = node.decimalValue();
            long millis = seconds.movePointRight(3).longValueExact();
            if (millis <= 0L) {
                throw new IllegalArgumentException(
                        "xAI video duration must be positive.");
            }
            return millis;
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(
                    "xAI video duration precision is invalid.", failure);
        }
    }

    private static Long optionalNonNegativeLong(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new IllegalArgumentException("xAI video cost ticks are invalid.");
        }
        long value = node.longValue();
        if (value < 0L) {
            throw new IllegalArgumentException("xAI video cost ticks are invalid.");
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().trim() : null;
    }
}
