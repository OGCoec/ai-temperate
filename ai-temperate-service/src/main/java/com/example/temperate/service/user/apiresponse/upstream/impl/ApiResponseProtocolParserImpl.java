package com.example.temperate.service.user.apiresponse.upstream.impl;

import com.example.temperate.service.user.aiinference.api.ApiInferenceUsage;
import com.example.temperate.service.user.aiinference.sse.ApiInferenceSseEvent;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseJsonResult;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseJsonResult.Status;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseProtocolParser;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseSseFrame;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseSseFrame.TerminalKind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 该实现是来拒绝事件名冲突、非法 sequence_number、畸形终态和缺失 Usage，同时原样保留合法未来事件及函数参数增量。
 */
@Service
public final class ApiResponseProtocolParserImpl
        implements ApiResponseProtocolParser {

    private final ObjectMapper objectMapper;

    public ApiResponseProtocolParserImpl(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public ApiResponseSseFrame parseSse(ApiInferenceSseEvent event) {
        if ("[DONE]".equals(event.data())) {
            return new ApiResponseSseFrame(
                    "message", event.data(), 0L, -1L,
                    TerminalKind.LEGACY_DONE, null, null);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(event.data());
        } catch (JsonProcessingException exception) {
            throw protocol("The model upstream returned malformed SSE JSON.");
        }
        if (root == null || !root.isObject()) {
            throw protocol("The model upstream returned a non-object SSE event.");
        }
        String type = textual(root.get("type"), "type");
        String declared = event.eventName();
        if (declared != null && !declared.isBlank() && !"message".equals(declared)
                && !declared.equals(type)) {
            throw protocol("The model upstream returned conflicting SSE event names.");
        }
        long sequence = nonNegativeInteger(root.get("sequence_number"), "sequence_number");
        String eventName = type;
        TerminalKind terminal = switch (type) {
            case "response.completed" -> TerminalKind.COMPLETED;
            case "response.incomplete" -> TerminalKind.INCOMPLETE;
            case "response.failed" -> TerminalKind.FAILED;
            case "error" -> TerminalKind.ERROR;
            default -> TerminalKind.NONE;
        };
        ApiInferenceUsage usage = null;
        String finishReason = null;
        if (terminal == TerminalKind.COMPLETED || terminal == TerminalKind.INCOMPLETE) {
            JsonNode response = requiredObject(root.get("response"), "response");
            String expectedStatus = terminal == TerminalKind.COMPLETED
                    ? "completed" : "incomplete";
            if (!expectedStatus.equals(textual(response.get("status"), "response.status"))) {
                throw protocol("The Responses event type conflicts with response.status.");
            }
            usage = parseUsage(response.get("usage"));
            finishReason = terminal == TerminalKind.COMPLETED
                    ? completedFinishReason(response)
                    : incompleteFinishReason(response);
        } else if (terminal == TerminalKind.FAILED) {
            JsonNode response = requiredObject(root.get("response"), "response");
            if (!"failed".equals(textual(response.get("status"), "response.status"))) {
                throw protocol("The Responses failure event conflicts with response.status.");
            }
            finishReason = "FAILED";
        } else if (terminal == TerminalKind.ERROR) {
            textual(root.get("message"), "message");
            finishReason = "ERROR";
        }
        long outputBytes = deltaBytes(type, root.get("delta"));
        return new ApiResponseSseFrame(
                eventName,
                event.data(),
                outputBytes,
                sequence,
                terminal,
                usage,
                finishReason);
    }

    @Override
    public ApiResponseJsonResult parseJson(JsonNode response) {
        JsonNode root = requiredObject(response, "response");
        if (!"response".equals(textual(root.get("object"), "object"))) {
            throw protocol("The model upstream returned an unexpected JSON object.");
        }
        String status = textual(root.get("status"), "status");
        return switch (status) {
            case "completed" -> new ApiResponseJsonResult(
                    root,
                    Status.COMPLETED,
                    parseUsage(root.get("usage")),
                    completedFinishReason(root));
            case "incomplete" -> new ApiResponseJsonResult(
                    root,
                    Status.INCOMPLETE,
                    parseUsage(root.get("usage")),
                    incompleteFinishReason(root));
            case "failed" -> new ApiResponseJsonResult(
                    root, Status.FAILED, null, "FAILED");
            default -> throw protocol("The model upstream returned an unknown response status.");
        };
    }

    private static ApiInferenceUsage parseUsage(JsonNode usageNode) {
        JsonNode usage = requiredObject(usageNode, "usage");
        long input = nonNegativeInteger(usage.get("input_tokens"), "usage.input_tokens");
        long output = nonNegativeInteger(usage.get("output_tokens"), "usage.output_tokens");
        long cached = 0L;
        JsonNode detailsNode = usage.get("input_tokens_details");
        if (detailsNode != null && !detailsNode.isNull()) {
            JsonNode details = requiredObject(detailsNode, "usage.input_tokens_details");
            JsonNode cachedNode = details.get("cached_tokens");
            if (cachedNode != null && !cachedNode.isNull()) {
                cached = nonNegativeInteger(cachedNode, "usage.input_tokens_details.cached_tokens");
            }
        }
        if (cached > input) {
            throw protocol("The model upstream returned invalid cached token usage.");
        }
        try {
            return new ApiInferenceUsage(input, output, cached);
        } catch (IllegalArgumentException exception) {
            throw protocol("The model upstream returned invalid token usage.");
        }
    }

    private static String completedFinishReason(JsonNode response) {
        JsonNode output = response.get("output");
        if (output != null && output.isArray()) {
            for (JsonNode item : output) {
                if (item.isObject() && "function_call".equals(item.path("type").textValue())) {
                    return "TOOL_CALLS";
                }
            }
        }
        return "STOP";
    }

    private static String incompleteFinishReason(JsonNode response) {
        JsonNode details = response.get("incomplete_details");
        if (details == null || !details.isObject()) {
            return "INCOMPLETE";
        }
        JsonNode reason = details.get("reason");
        if (reason == null || !reason.isTextual()) {
            return "INCOMPLETE";
        }
        String normalized = reason.textValue().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z0-9_]{1,64}")
                ? normalized : "INCOMPLETE";
    }

    private static long deltaBytes(String type, JsonNode delta) {
        if (!(type.endsWith(".delta")
                && (type.contains("output_text")
                || type.contains("reasoning")
                || type.contains("function_call_arguments")))) {
            return 0L;
        }
        if (delta == null || !delta.isTextual()) {
            throw protocol("The model upstream returned an invalid delta event.");
        }
        return delta.textValue().getBytes(StandardCharsets.UTF_8).length;
    }

    private static JsonNode requiredObject(JsonNode node, String parameter) {
        if (node == null || !node.isObject()) {
            throw protocol("The model upstream returned an invalid " + parameter + ".");
        }
        return node;
    }

    private static String textual(JsonNode node, String parameter) {
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw protocol("The model upstream returned an invalid " + parameter + ".");
        }
        return node.textValue();
    }

    private static long nonNegativeInteger(JsonNode node, String parameter) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()
                || node.longValue() < 0) {
            throw protocol("The model upstream returned an invalid " + parameter + ".");
        }
        return node.longValue();
    }

    private static ApiChatException protocol(String message) {
        return new ApiChatException(
                ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR,
                message,
                null);
    }
}
