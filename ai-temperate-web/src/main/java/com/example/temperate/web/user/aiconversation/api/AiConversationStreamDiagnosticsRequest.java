package com.example.temperate.web.user.aiconversation.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamClientDiagnostic;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 定义浏览器回传的 SSE 聚合诊断契约；字段只包含时间、计数和安全关联标识，禁止承载模型正文。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AiConversationStreamDiagnosticsRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$")
        String usagePublicId,
        @Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        String traceId,
        @NotBlank
        @Pattern(regexp = "^[A-Z_]{1,32}$")
        String outcome,
        @Min(-1) @Max(3_600_000) long responseHeadersMs,
        @Min(-1) @Max(3_600_000) long firstByteMs,
        @Min(-1) @Max(3_600_000) long lastNetworkByteMs,
        @Min(-1) @Max(3_600_000) long firstHeartbeatMs,
        @Min(-1) @Max(3_600_000) long firstDeltaMs,
        @Min(-1) @Max(3_600_000) long completedMs,
        @Min(0) @Max(1_000_000) long networkReads,
        @Min(0) @Max(104_857_600) long networkBytes,
        @Min(0) @Max(1_000_000) long parsedEvents,
        @Min(0) @Max(1_000_000) long renderedUpdates,
        @Min(0) @Max(10_000_000) long renderedTextCharacters,
        @Min(-1) @Max(1_000_000_000) long lastDeltaSequence,
        @Min(0) @Max(1_000_000) long deltaSequenceGapCount) {

    /**
     * 在 Controller 完成 Bean Validation 后，把外部 JSON 的受限字段转换为 Service 输入，
     * 避免 Web 层对象进入后端业务与日志边界。
     */
    public AiConversationStreamClientDiagnostic toDiagnostic() {
        return new AiConversationStreamClientDiagnostic(
                usagePublicId,
                traceId,
                outcome,
                responseHeadersMs,
                firstByteMs,
                lastNetworkByteMs,
                firstHeartbeatMs,
                firstDeltaMs,
                completedMs,
                networkReads,
                networkBytes,
                parsedEvents,
                renderedUpdates,
                renderedTextCharacters,
                lastDeltaSequence,
                deltaSequenceGapCount);
    }
}
