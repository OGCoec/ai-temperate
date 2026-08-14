package com.example.temperate.service.user.apichat.impl;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apichat.ApiChatRequest;
import com.example.temperate.service.user.apichat.ApiChatRequest.FunctionDefinition;
import com.example.temperate.service.user.apichat.ApiChatRequest.Message;
import com.example.temperate.service.user.apichat.ApiChatRequest.Tool;
import com.example.temperate.service.user.apichat.ApiChatRequest.ToolCall;
import com.example.temperate.service.user.apichat.ApiChatRequestValidator;
import com.example.temperate.service.user.apichat.ValidatedApiChatRequest;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 该实现是来拒绝数字字符串、未知字段、多模态和无界工具 Schema，并让同一有效 Token 上限同时进入上下文校验、预扣与上游请求。
 */
@Service
public final class ApiChatRequestValidatorImpl implements ApiChatRequestValidator {

    private static final Pattern FUNCTION_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Set<String> ROLES = Set.of("system", "user", "assistant", "tool");
    private static final int MAX_SCHEMA_DEPTH = 16;
    private static final int MAX_SCHEMA_NODES = 2_000;

    private final AiModelCacheService modelCacheService;
    private final ApiKeyProperties properties;
    private final ObjectMapper objectMapper;

    public ApiChatRequestValidatorImpl(
            AiModelCacheService modelCacheService,
            ApiKeyProperties properties,
            ObjectMapper objectMapper) {
        this.modelCacheService = Objects.requireNonNull(modelCacheService);
        this.properties = Objects.requireNonNull(properties);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public ValidatedApiChatRequest validate(
            ApiKeyPrincipal principal,
            ApiChatRequest request) {
        if (principal == null || request == null) {
            throw ApiChatException.invalid("Request is required.", null);
        }
        enforceBodySize(request);
        if (request.model() == null || request.model().isBlank() || request.model().length() > 128) {
            throw ApiChatException.invalid("Model is required.", "model");
        }
        AiModelCacheEntry model = findModel(request.model());
        if (!principal.modelIds().contains(model.id())) {
            throw new ApiChatException(
                    ApiChatErrorCode.MODEL_NOT_ALLOWED,
                    "The API Key is not authorized for this model.",
                    "model");
        }
        if (!model.capabilities().contains(AiModelCapabilityCode.CHAT_COMPLETIONS)
                || model.contextWindowTokens() <= 0
                || model.maxOutputTokens() <= 0) {
            throw new ApiChatException(
                    ApiChatErrorCode.MODEL_NOT_FOUND,
                    "The requested model is unavailable.",
                    "model");
        }

        if (request.stream() == null) {
            throw new ApiChatException(
                    ApiChatErrorCode.STREAM_REQUIRED,
                    "Only stream=true is supported.",
                    "stream");
        }
        if (!request.stream().isBoolean()) {
            throw ApiChatException.invalid(
                    "stream must be a JSON boolean.", "stream");
        }
        if (!request.stream().booleanValue()) {
            throw new ApiChatException(
                    ApiChatErrorCode.STREAM_REQUIRED,
                    "Only stream=true is supported.",
                    "stream");
        }
        boolean includeUsage = request.streamOptions() != null
                && request.streamOptions().includeUsage() != null
                && booleanValue(
                request.streamOptions().includeUsage(),
                "stream_options.include_usage");
        validateMessages(request.messages());
        validateTools(request.tools());
        validateOptionalParameters(request);
        validateToolParameterRelationships(request);

        if (request.maxCompletionTokens() != null && request.maxTokens() != null) {
            throw ApiChatException.invalid(
                    "max_completion_tokens and max_tokens cannot both be provided.",
                    "max_completion_tokens");
        }
        JsonNode clientLimitNode = request.maxCompletionTokens() != null
                ? request.maxCompletionTokens() : request.maxTokens();
        long effectiveMax = clientLimitNode == null
                ? model.maxOutputTokens()
                : Math.min(positiveInteger(clientLimitNode,
                request.maxCompletionTokens() != null
                        ? "max_completion_tokens" : "max_tokens"),
                model.maxOutputTokens());
        long estimatedPrompt = estimatePromptTokens(request);
        if (estimatedPrompt > model.contextWindowTokens() - effectiveMax) {
            throw new ApiChatException(
                    ApiChatErrorCode.CONTEXT_LENGTH_EXCEEDED,
                    "The request exceeds the model context window.",
                    "messages");
        }
        return new ValidatedApiChatRequest(
                request, model, effectiveMax, estimatedPrompt, includeUsage);
    }

    private AiModelCacheEntry findModel(String requested) {
        String normalized = requested.trim().toLowerCase(Locale.ROOT);
        return modelCacheService.getOrLoadEnabledSnapshot().models().stream()
                .filter(model -> model.modelName().equalsIgnoreCase(normalized))
                .findFirst()
                .orElseThrow(() -> new ApiChatException(
                        ApiChatErrorCode.MODEL_NOT_FOUND,
                        "The requested model does not exist or is disabled.",
                        "model"));
    }

    private void enforceBodySize(ApiChatRequest request) {
        try {
            if (objectMapper.writeValueAsBytes(request).length
                    > properties.getRequest().getMaxBodyBytes()) {
                throw ApiChatException.invalid("Request body exceeds 1 MiB.", null);
            }
        } catch (JsonProcessingException exception) {
            throw ApiChatException.invalid("Request body cannot be encoded.", null);
        }
    }

    private void validateMessages(List<Message> messages) {
        if (messages == null
                || messages.isEmpty()
                || messages.size() > properties.getRequest().getMaxMessages()) {
            throw ApiChatException.invalid("messages must contain 1 to 256 items.", "messages");
        }
        Set<String> declaredToolCallIds = new HashSet<>();
        for (Message message : messages) {
            if (message == null || !ROLES.contains(message.role())) {
                throw ApiChatException.invalid("Message role is unsupported.", "messages");
            }
            boolean text = message.content() != null && message.content().isTextual();
            boolean nullContent = message.content() == null || message.content().isNull();
            if (!(text || ("assistant".equals(message.role()) && nullContent))) {
                throw ApiChatException.invalid(
                        "Message content must be a string; multimodal arrays are unsupported.",
                        "messages");
            }
            if (text && message.content().textValue().length() > 262_144) {
                throw ApiChatException.invalid("Message content is too large.", "messages");
            }
            if ("assistant".equals(message.role())) {
                validateAssistantToolCalls(message.toolCalls());
                if (message.toolCalls() != null) {
                    for (ToolCall call : message.toolCalls()) {
                        if (!declaredToolCallIds.add(call.id())) {
                            throw ApiChatException.invalid(
                                    "Assistant tool_call IDs must be unique.", "messages");
                        }
                    }
                }
                if (!text && (message.toolCalls() == null || message.toolCalls().isEmpty())) {
                    throw ApiChatException.invalid(
                            "Assistant message requires content or tool_calls.", "messages");
                }
            } else if (message.toolCalls() != null) {
                throw ApiChatException.invalid(
                        "Only assistant messages may contain tool_calls.", "messages");
            }
            if ("tool".equals(message.role())) {
                if (message.toolCallId() == null || message.toolCallId().isBlank()
                        || message.toolCallId().length() > 128) {
                    throw ApiChatException.invalid(
                            "Tool message requires tool_call_id.", "messages");
                }
                if (!declaredToolCallIds.contains(message.toolCallId())) {
                    throw ApiChatException.invalid(
                            "Tool message references an unknown tool_call_id.", "messages");
                }
            } else if (message.toolCallId() != null) {
                throw ApiChatException.invalid(
                        "Only tool messages may contain tool_call_id.", "messages");
            }
        }
    }

    private void validateAssistantToolCalls(List<ToolCall> toolCalls) {
        if (toolCalls == null) {
            return;
        }
        if (toolCalls.isEmpty() || toolCalls.size() > 128) {
            throw ApiChatException.invalid("assistant tool_calls size is invalid.", "messages");
        }
        Set<String> ids = new HashSet<>();
        for (ToolCall call : toolCalls) {
            if (call == null || call.id() == null || call.id().isBlank()
                    || call.id().length() > 128 || !ids.add(call.id())
                    || !"function".equals(call.type())
                    || call.function() == null
                    || !validFunctionName(call.function().name())
                    || call.function().arguments() == null
                    || call.function().arguments().length() > 262_144) {
                throw ApiChatException.invalid("Assistant tool_call is invalid.", "messages");
            }
        }
    }

    private void validateTools(List<Tool> tools) {
        if (tools == null) {
            return;
        }
        if (tools.size() > properties.getRequest().getMaxTools()) {
            throw ApiChatException.invalid("Too many tools were provided.", "tools");
        }
        Set<String> names = new HashSet<>();
        for (Tool tool : tools) {
            FunctionDefinition function = tool == null ? null : tool.function();
            if (tool == null || !"function".equals(tool.type())
                    || function == null
                    || !validFunctionName(function.name())
                    || !names.add(function.name())
                    || (function.description() != null && function.description().length() > 1024)
                    || function.parameters() == null
                    || !function.parameters().isObject()) {
                throw ApiChatException.invalid("Function tool is invalid.", "tools");
            }
            int[] nodes = {0};
            validateSchema(function.parameters(), 1, nodes);
        }
    }

    private void validateSchema(JsonNode node, int depth, int[] nodes) {
        nodes[0]++;
        if (depth > MAX_SCHEMA_DEPTH || nodes[0] > MAX_SCHEMA_NODES) {
            throw ApiChatException.invalid("Function JSON Schema is too complex.", "tools");
        }
        if (node.isContainerNode()) {
            node.elements().forEachRemaining(child -> validateSchema(child, depth + 1, nodes));
        } else if (!node.isValueNode()) {
            throw ApiChatException.invalid("Function JSON Schema is invalid.", "tools");
        }
    }

    private void validateOptionalParameters(ApiChatRequest request) {
        numberInRange(request.temperature(), "temperature", 0, 2);
        numberInRange(request.topP(), "top_p", 0, 1);
        numberInRange(request.presencePenalty(), "presence_penalty", -2, 2);
        numberInRange(request.frequencyPenalty(), "frequency_penalty", -2, 2);
        if (request.seed() != null) {
            integer(request.seed(), "seed");
        }
        if (request.n() != null && integer(request.n(), "n") != 1) {
            throw ApiChatException.invalid("n must equal 1.", "n");
        }
        if (request.parallelToolCalls() != null) {
            booleanValue(request.parallelToolCalls(), "parallel_tool_calls");
        }
        validateStop(request.stop());
        validateToolChoice(request.toolChoice());
    }

    private void validateToolParameterRelationships(ApiChatRequest request) {
        boolean hasTools = request.tools() != null && !request.tools().isEmpty();
        if (!hasTools && (request.toolChoice() != null
                || request.parallelToolCalls() != null)) {
            throw ApiChatException.invalid(
                    "tool_choice and parallel_tool_calls require tools.", "tools");
        }
        if (!hasTools || request.toolChoice() == null || !request.toolChoice().isObject()) {
            return;
        }
        String selected = request.toolChoice().path("function").path("name").textValue();
        boolean declared = request.tools().stream()
                .map(Tool::function)
                .map(FunctionDefinition::name)
                .anyMatch(selected::equals);
        if (!declared) {
            throw ApiChatException.invalid(
                    "tool_choice references an undeclared function.", "tool_choice");
        }
    }

    private void validateStop(JsonNode stop) {
        if (stop == null || stop.isNull()) {
            return;
        }
        if (stop.isTextual()) {
            if (stop.textValue().length() > 1024) {
                throw ApiChatException.invalid("stop is too long.", "stop");
            }
            return;
        }
        if (!stop.isArray() || stop.isEmpty() || stop.size() > 4) {
            throw ApiChatException.invalid("stop must be a string or up to four strings.", "stop");
        }
        for (JsonNode item : stop) {
            if (!item.isTextual() || item.textValue().length() > 1024) {
                throw ApiChatException.invalid("stop contains an invalid item.", "stop");
            }
        }
    }

    private void validateToolChoice(JsonNode choice) {
        if (choice == null) {
            return;
        }
        if (choice.isTextual()) {
            if (!Set.of("none", "auto", "required").contains(choice.textValue())) {
                throw ApiChatException.invalid("tool_choice is unsupported.", "tool_choice");
            }
            return;
        }
        if (!choice.isObject()
                || choice.size() != 2
                || !choice.has("type")
                || !choice.has("function")
                || !choice.get("type").isTextual()
                || !"function".equals(choice.get("type").textValue())
                || !choice.get("function").isObject()
                || choice.get("function").size() != 1
                || !choice.get("function").has("name")
                || !choice.get("function").get("name").isTextual()
                || !validFunctionName(choice.get("function").get("name").textValue())) {
            throw ApiChatException.invalid("tool_choice object is invalid.", "tool_choice");
        }
    }

    private long estimatePromptTokens(ApiChatRequest request) {
        long bytes = 0;
        long overhead = 0;
        for (Message message : request.messages()) {
            overhead = Math.addExact(overhead, 8);
            if (message.content() != null && message.content().isTextual()) {
                bytes = Math.addExact(bytes,
                        message.content().textValue().getBytes(StandardCharsets.UTF_8).length);
            }
            if (message.toolCalls() != null) {
                for (ToolCall call : message.toolCalls()) {
                    bytes = Math.addExact(bytes,
                            call.function().arguments().getBytes(StandardCharsets.UTF_8).length);
                    overhead = Math.addExact(overhead, 12);
                }
            }
        }
        if (request.tools() != null) {
            for (Tool tool : request.tools()) {
                try {
                    bytes = Math.addExact(bytes,
                            objectMapper.writeValueAsBytes(tool).length);
                    overhead = Math.addExact(overhead, 12);
                } catch (JsonProcessingException exception) {
                    throw ApiChatException.invalid("Tool cannot be encoded.", "tools");
                }
            }
        }
        return Math.addExact(Math.ceilDiv(bytes, 3L), overhead);
    }

    private static boolean booleanValue(JsonNode node, String parameter) {
        if (node == null || !node.isBoolean()) {
            throw ApiChatException.invalid(parameter + " must be a JSON boolean.", parameter);
        }
        return node.booleanValue();
    }

    private static long positiveInteger(JsonNode node, String parameter) {
        long value = integer(node, parameter);
        if (value <= 0) {
            throw ApiChatException.invalid(parameter + " must be positive.", parameter);
        }
        return value;
    }

    private static long integer(JsonNode node, String parameter) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw ApiChatException.invalid(parameter + " must be a JSON integer.", parameter);
        }
        return node.longValue();
    }

    private static void numberInRange(
            JsonNode node,
            String parameter,
            double minimum,
            double maximum) {
        if (node == null) {
            return;
        }
        if (!node.isNumber()) {
            throw ApiChatException.invalid(parameter + " must be a JSON number.", parameter);
        }
        double value = node.doubleValue();
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw ApiChatException.invalid(parameter + " is outside its supported range.", parameter);
        }
    }

    private static boolean validFunctionName(String name) {
        return name != null && FUNCTION_NAME.matcher(name).matches();
    }
}
