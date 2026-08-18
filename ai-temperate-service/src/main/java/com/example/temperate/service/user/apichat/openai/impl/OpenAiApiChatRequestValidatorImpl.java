package com.example.temperate.service.user.apichat.openai.impl;

import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apichat.openai.OpenAiApiChatRequestValidation;
import com.example.temperate.service.user.apichat.openai.OpenAiApiChatRequestValidator;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 该实现是来对 OpenAI Chat 常用文本、函数和结构化输出字段做递归白名单校验，拒绝状态、多模态与托管工具。
 */
@Service
public final class OpenAiApiChatRequestValidatorImpl
        implements OpenAiApiChatRequestValidator {

    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "model", "messages", "stream", "stream_options",
            "max_completion_tokens", "max_tokens", "reasoning_effort",
            "service_tier", "verbosity", "safety_identifier", "user",
            "temperature", "top_p", "presence_penalty", "frequency_penalty",
            "stop", "seed", "n", "logprobs", "top_logprobs", "prediction",
            "prompt_cache_key", "prompt_cache_options", "tools", "tool_choice",
            "parallel_tool_calls", "functions", "function_call",
            "response_format", "store");
    private static final Set<String> ROLES = Set.of(
            "developer", "system", "user", "assistant", "tool", "function");
    private static final Set<String> REASONING_EFFORTS = Set.of(
            "none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra");
    private static final Set<String> SERVICE_TIERS = Set.of(
            "auto", "default", "flex", "scale", "priority");
    private static final Set<String> VERBOSITIES = Set.of("low", "medium", "high");
    private static final Pattern FUNCTION_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final int MAX_TEXT_CHARS = 262_144;
    private static final int MAX_SCHEMA_DEPTH = 16;
    private static final int MAX_SCHEMA_NODES = 2_000;

    private final ObjectMapper objectMapper;
    private final ApiKeyProperties properties;

    public OpenAiApiChatRequestValidatorImpl(
            ObjectMapper objectMapper,
            ApiKeyProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public OpenAiApiChatRequestValidation validate(ObjectNode request) {
        if (request == null) {
            throw invalid("Request is required.", null);
        }
        enforceBodySize(request);
        requireOnlyFields(request, TOP_LEVEL_FIELDS, "");
        String model = requiredText(request.get("model"), "model", 128);
        validateMessages(request.get("messages"));
        boolean stream = optionalBoolean(request.get("stream"), "stream", false);
        boolean includeUsage = validateStreamOptions(
                request.get("stream_options"), stream);
        Long requestedMax = validateTokenLimit(request);
        validateOptionalParameters(request);
        boolean functionTools = validateTools(request.get("tools"));
        functionTools |= validateLegacyFunctions(request.get("functions"));
        validateToolChoices(request, functionTools);
        boolean structuredOutput = validateResponseFormat(request.get("response_format"));
        validatePrediction(request.get("prediction"));
        validateStore(request.get("store"));
        return new OpenAiApiChatRequestValidation(
                request, model, stream, requestedMax, includeUsage,
                functionTools, structuredOutput);
    }

    private void enforceBodySize(ObjectNode request) {
        try {
            if (objectMapper.writeValueAsBytes(request).length
                    > properties.getRequest().getMaxBodyBytes()) {
                throw invalid("Request body exceeds the allowed UTF-8 size.", null);
            }
        } catch (JsonProcessingException failure) {
            throw invalid("Request body cannot be encoded.", null);
        }
    }

    private void validateMessages(JsonNode node) {
        if (!(node instanceof ArrayNode messages)
                || messages.isEmpty()
                || messages.size() > properties.getRequest().getMaxMessages()) {
            throw invalid("messages must contain a bounded non-empty array.", "messages");
        }
        for (int index = 0; index < messages.size(); index++) {
            String parameter = "messages[" + index + "]";
            if (!(messages.get(index) instanceof ObjectNode message)) {
                throw invalid("Message must be an object.", parameter);
            }
            String role = requiredText(message.get("role"), parameter + ".role", 32);
            if (!ROLES.contains(role)) {
                throw unsupported(parameter + ".role");
            }
            Set<String> allowed = switch (role) {
                case "developer", "system", "user" -> Set.of("role", "content", "name");
                case "assistant" -> Set.of(
                        "role", "content", "name", "reasoning_content",
                        "tool_calls", "function_call", "refusal");
                case "tool" -> Set.of("role", "content", "tool_call_id");
                case "function" -> Set.of("role", "content", "name");
                default -> throw unsupported(parameter + ".role");
            };
            requireOnlyFields(message, allowed, parameter + ".");
            optionalText(message.get("name"), parameter + ".name", 64);
            validateMessageContent(message.get("content"), parameter + ".content", role);
            optionalText(message.get("reasoning_content"),
                    parameter + ".reasoning_content", MAX_TEXT_CHARS);
            optionalText(message.get("refusal"), parameter + ".refusal", MAX_TEXT_CHARS);
            if ("assistant".equals(role)) {
                validateToolCalls(message.get("tool_calls"), parameter + ".tool_calls");
                validateFunctionCall(message.get("function_call"),
                        parameter + ".function_call");
            }
            if ("tool".equals(role)) {
                requiredText(message.get("tool_call_id"),
                        parameter + ".tool_call_id", 128);
            }
            if ("function".equals(role)) {
                requiredFunctionName(message.get("name"), parameter + ".name");
            }
        }
    }

    private void validateMessageContent(JsonNode content, String parameter, String role) {
        if (content == null || content.isNull()) {
            if (!"assistant".equals(role)) {
                throw invalid("Message content is required.", parameter);
            }
            return;
        }
        if (content.isTextual()) {
            boundedText(content, parameter);
            return;
        }
        if (!(content instanceof ArrayNode parts) || parts.isEmpty()
                || "tool".equals(role) || "function".equals(role)) {
            throw invalid("Message content must be text or text parts.", parameter);
        }
        long totalChars = 0;
        for (int index = 0; index < parts.size(); index++) {
            String partParameter = parameter + "[" + index + "]";
            if (!(parts.get(index) instanceof ObjectNode part)) {
                throw invalid("Content part must be an object.", partParameter);
            }
            String type = requiredText(part.get("type"), partParameter + ".type", 32);
            if (!"text".equals(type)) {
                throw unsupported(partParameter + ".type");
            }
            requireOnlyFields(part,
                    Set.of("type", "text", "prompt_cache_breakpoint"),
                    partParameter + ".");
            String text = requiredText(part.get("text"),
                    partParameter + ".text", MAX_TEXT_CHARS);
            totalChars = Math.addExact(totalChars, text.length());
            validateCacheBreakpoint(part.get("prompt_cache_breakpoint"),
                    partParameter + ".prompt_cache_breakpoint");
        }
        if (totalChars > MAX_TEXT_CHARS) {
            throw invalid("Message content is too long.", parameter);
        }
    }

    private void validateToolCalls(JsonNode node, String parameter) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!(node instanceof ArrayNode calls) || calls.isEmpty()) {
            throw invalid("tool_calls must be a non-empty array.", parameter);
        }
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < calls.size(); index++) {
            String itemParameter = parameter + "[" + index + "]";
            if (!(calls.get(index) instanceof ObjectNode call)) {
                throw invalid("Tool call must be an object.", itemParameter);
            }
            requireOnlyFields(call, Set.of("id", "type", "function"),
                    itemParameter + ".");
            String id = requiredText(call.get("id"), itemParameter + ".id", 128);
            if (!ids.add(id)) {
                throw invalid("Tool call IDs must be unique.", itemParameter + ".id");
            }
            if (!"function".equals(requiredText(
                    call.get("type"), itemParameter + ".type", 32))) {
                throw unsupported(itemParameter + ".type");
            }
            validateFunctionCall(call.get("function"), itemParameter + ".function");
        }
    }

    private boolean validateTools(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (!(node instanceof ArrayNode tools)
                || tools.size() > properties.getRequest().getMaxTools()) {
            throw invalid("tools must be a bounded array.", "tools");
        }
        validateSerializedToolBudget(tools, "tools");
        for (int index = 0; index < tools.size(); index++) {
            String parameter = "tools[" + index + "]";
            if (!(tools.get(index) instanceof ObjectNode tool)) {
                throw invalid("Tool must be an object.", parameter);
            }
            requireOnlyFields(tool, Set.of("type", "function"), parameter + ".");
            if (!"function".equals(requiredText(tool.get("type"),
                    parameter + ".type", 32))) {
                throw unsupported(parameter + ".type");
            }
            validateFunctionDefinition(tool.get("function"), parameter + ".function");
        }
        return !tools.isEmpty();
    }

    private boolean validateLegacyFunctions(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (!(node instanceof ArrayNode functions)
                || functions.size() > properties.getRequest().getMaxTools()) {
            throw invalid("functions must be a bounded array.", "functions");
        }
        validateSerializedToolBudget(functions, "functions");
        for (int index = 0; index < functions.size(); index++) {
            validateFunctionDefinition(functions.get(index),
                    "functions[" + index + "]");
        }
        return !functions.isEmpty();
    }

    private void validateFunctionDefinition(JsonNode node, String parameter) {
        if (!(node instanceof ObjectNode function)) {
            throw invalid("Function definition must be an object.", parameter);
        }
        requireOnlyFields(function,
                Set.of("name", "description", "parameters", "strict"),
                parameter + ".");
        requiredFunctionName(function.get("name"), parameter + ".name");
        JsonNode description = function.get("description");
        optionalText(description, parameter + ".description", MAX_TEXT_CHARS);
        if (description != null && description.isTextual()
                && description.textValue().getBytes(StandardCharsets.UTF_8).length
                > properties.getRequest().getMaxToolDescriptionBytes()) {
            throw invalid("Function description exceeds the allowed UTF-8 size.",
                    parameter + ".description");
        }
        JsonNode schema = function.get("parameters");
        if (schema != null && !schema.isNull()) {
            validateSchema(schema, 0, new int[]{0}, parameter + ".parameters");
        }
        optionalBoolean(function.get("strict"), parameter + ".strict", false);
    }

    private void validateFunctionCall(JsonNode node, String parameter) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!(node instanceof ObjectNode function)) {
            throw invalid("Function call must be an object.", parameter);
        }
        requireOnlyFields(function, Set.of("name", "arguments"), parameter + ".");
        requiredFunctionName(function.get("name"), parameter + ".name");
        requiredText(function.get("arguments"), parameter + ".arguments", MAX_TEXT_CHARS);
    }

    private void validateToolChoices(ObjectNode request, boolean hasFunctions) {
        validateToolChoice(request.get("tool_choice"), "tool_choice", hasFunctions, false);
        validateToolChoice(request.get("function_call"),
                "function_call", hasFunctions, true);
        if (request.hasNonNull("parallel_tool_calls")) {
            boolean parallel = optionalBoolean(request.get("parallel_tool_calls"),
                    "parallel_tool_calls", false);
            if (parallel && !hasFunctions) {
                throw invalid("parallel_tool_calls requires functions.",
                        "parallel_tool_calls");
            }
        }
    }

    private void validateToolChoice(
            JsonNode node,
            String parameter,
            boolean hasFunctions,
            boolean legacy) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            Set<String> allowed = legacy
                    ? Set.of("none", "auto") : Set.of("none", "auto", "required");
            if (!allowed.contains(node.textValue())) {
                throw invalid("Tool choice is invalid.", parameter);
            }
        } else if (node instanceof ObjectNode object) {
            if (legacy) {
                requireOnlyFields(object, Set.of("name"), parameter + ".");
                requiredFunctionName(object.get("name"), parameter + ".name");
            } else {
                requireOnlyFields(object, Set.of("type", "function"), parameter + ".");
                if (!"function".equals(requiredText(
                        object.get("type"), parameter + ".type", 32))) {
                    throw unsupported(parameter + ".type");
                }
                JsonNode function = object.get("function");
                if (!(function instanceof ObjectNode functionObject)) {
                    throw invalid("tool_choice.function must be an object.",
                            parameter + ".function");
                }
                requireOnlyFields(functionObject, Set.of("name"),
                        parameter + ".function.");
                requiredFunctionName(functionObject.get("name"),
                        parameter + ".function.name");
            }
        } else {
            throw invalid("Tool choice has an invalid JSON type.", parameter);
        }
        if (!hasFunctions && !(node.isTextual() && "none".equals(node.textValue()))) {
            throw invalid("Tool choice requires a function definition.", parameter);
        }
    }

    private boolean validateResponseFormat(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (!(node instanceof ObjectNode format)) {
            throw invalid("response_format must be an object.", "response_format");
        }
        String type = requiredText(format.get("type"), "response_format.type", 32);
        switch (type) {
            case "text", "json_object" -> requireOnlyFields(
                    format, Set.of("type"), "response_format.");
            case "json_schema" -> {
                requireOnlyFields(format, Set.of("type", "json_schema"),
                        "response_format.");
                validateChatJsonSchema(format.get("json_schema"));
            }
            default -> throw unsupported("response_format.type");
        }
        return !"text".equals(type);
    }

    private void validateChatJsonSchema(JsonNode node) {
        if (!(node instanceof ObjectNode definition)) {
            throw invalid("response_format.json_schema must be an object.",
                    "response_format.json_schema");
        }
        requireOnlyFields(definition, Set.of("name", "description", "schema", "strict"),
                "response_format.json_schema.");
        requiredFunctionName(definition.get("name"), "response_format.json_schema.name");
        optionalText(definition.get("description"),
                "response_format.json_schema.description", MAX_TEXT_CHARS);
        validateSchema(definition.get("schema"), 0, new int[]{0},
                "response_format.json_schema.schema");
        optionalBoolean(definition.get("strict"),
                "response_format.json_schema.strict", false);
    }

    private void validatePrediction(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!(node instanceof ObjectNode prediction)) {
            throw invalid("prediction must be an object.", "prediction");
        }
        requireOnlyFields(prediction, Set.of("type", "content"), "prediction.");
        if (!"content".equals(requiredText(
                prediction.get("type"), "prediction.type", 32))) {
            throw unsupported("prediction.type");
        }
        JsonNode content = prediction.get("content");
        if (content != null && content.isTextual()) {
            boundedText(content, "prediction.content");
            return;
        }
        if (!(content instanceof ArrayNode parts) || parts.isEmpty()) {
            throw invalid("prediction.content must be text or text parts.",
                    "prediction.content");
        }
        for (int index = 0; index < parts.size(); index++) {
            String parameter = "prediction.content[" + index + "]";
            if (!(parts.get(index) instanceof ObjectNode part)) {
                throw invalid("Prediction part must be an object.", parameter);
            }
            requireOnlyFields(part, Set.of("type", "text"), parameter + ".");
            if (!"text".equals(requiredText(part.get("type"), parameter + ".type", 32))) {
                throw unsupported(parameter + ".type");
            }
            requiredText(part.get("text"), parameter + ".text", MAX_TEXT_CHARS);
        }
    }

    private void validateOptionalParameters(ObjectNode request) {
        optionalEnum(request.get("reasoning_effort"), "reasoning_effort", REASONING_EFFORTS);
        optionalEnum(request.get("service_tier"), "service_tier", SERVICE_TIERS);
        optionalEnum(request.get("verbosity"), "verbosity", VERBOSITIES);
        optionalText(request.get("safety_identifier"), "safety_identifier", 512);
        optionalText(request.get("user"), "user", 512);
        optionalText(request.get("prompt_cache_key"), "prompt_cache_key", 256);
        numberInRange(request.get("temperature"), "temperature", 0, 2);
        numberInRange(request.get("top_p"), "top_p", 0, 1);
        numberInRange(request.get("presence_penalty"), "presence_penalty", -2, 2);
        numberInRange(request.get("frequency_penalty"), "frequency_penalty", -2, 2);
        optionalInteger(request.get("seed"), "seed", Long.MIN_VALUE, Long.MAX_VALUE);
        optionalInteger(request.get("n"), "n", 1, 128);
        optionalBoolean(request.get("logprobs"), "logprobs", false);
        optionalInteger(request.get("top_logprobs"), "top_logprobs", 0, 20);
        if (request.hasNonNull("top_logprobs")
                && !optionalBoolean(request.get("logprobs"), "logprobs", false)) {
            throw invalid("top_logprobs requires logprobs=true.", "top_logprobs");
        }
        validateStop(request.get("stop"));
        validateCacheOptions(request.get("prompt_cache_options"));
    }

    private void validateStop(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            boundedText(node, "stop");
            return;
        }
        if (!(node instanceof ArrayNode values) || values.isEmpty() || values.size() > 4) {
            throw invalid("stop must be text or an array of up to four strings.", "stop");
        }
        for (int index = 0; index < values.size(); index++) {
            requiredText(values.get(index), "stop[" + index + "]", MAX_TEXT_CHARS);
        }
    }

    private void validateCacheOptions(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!(node instanceof ObjectNode options)) {
            throw invalid("prompt_cache_options must be an object.", "prompt_cache_options");
        }
        requireOnlyFields(options, Set.of("ttl"), "prompt_cache_options.");
        optionalEnum(options.get("ttl"), "prompt_cache_options.ttl", Set.of("in-memory", "24h"));
    }

    private void validateCacheBreakpoint(JsonNode node, String parameter) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!(node instanceof ObjectNode breakpoint)) {
            throw invalid("Prompt cache breakpoint must be an object.", parameter);
        }
        requireOnlyFields(breakpoint, Set.of("mode"), parameter + ".");
        if (!"explicit".equals(requiredText(
                breakpoint.get("mode"), parameter + ".mode", 32))) {
            throw unsupported(parameter + ".mode");
        }
    }

    private boolean validateStreamOptions(JsonNode node, boolean stream) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (!(node instanceof ObjectNode options)) {
            throw invalid("stream_options must be an object.", "stream_options");
        }
        requireOnlyFields(options, Set.of("include_usage"), "stream_options.");
        boolean includeUsage = optionalBoolean(
                options.get("include_usage"), "stream_options.include_usage", false);
        if (!stream) {
            throw invalid("stream_options requires stream=true.", "stream_options");
        }
        return includeUsage;
    }

    private Long validateTokenLimit(ObjectNode request) {
        JsonNode current = request.get("max_completion_tokens");
        JsonNode legacy = request.get("max_tokens");
        if (current != null && !current.isNull() && legacy != null && !legacy.isNull()) {
            throw invalid("max_completion_tokens and max_tokens cannot both be provided.",
                    "max_completion_tokens");
        }
        JsonNode selected = current != null && !current.isNull() ? current : legacy;
        if (selected == null || selected.isNull()) {
            return null;
        }
        return optionalInteger(selected,
                current != null && !current.isNull()
                        ? "max_completion_tokens" : "max_tokens",
                1, Long.MAX_VALUE);
    }

    private void validateStore(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isBoolean()) {
            throw invalid("store must be a JSON boolean.", "store");
        }
        if (node.booleanValue()) {
            throw unsupported("store");
        }
    }

    private void validateSerializedToolBudget(JsonNode tools, String parameter) {
        try {
            if (objectMapper.writeValueAsBytes(tools).length
                    > properties.getRequest().getMaxToolDefinitionsBytes()) {
                throw invalid("Tool definitions exceed the allowed UTF-8 size.", parameter);
            }
        } catch (JsonProcessingException failure) {
            throw invalid("Tool definitions cannot be encoded.", parameter);
        }
    }

    private void validateSchema(JsonNode node, int depth, int[] nodes, String parameter) {
        if (node == null || node.isNull() || !node.isContainerNode()) {
            if (node == null || node.isNull()) {
                throw invalid("JSON Schema is required.", parameter);
            }
            return;
        }
        if (depth > MAX_SCHEMA_DEPTH || ++nodes[0] > MAX_SCHEMA_NODES) {
            throw invalid("JSON Schema exceeds the allowed complexity.", parameter);
        }
        for (JsonNode child : node) {
            validateSchema(child, depth + 1, nodes, parameter);
        }
    }

    private static void requireOnlyFields(
            ObjectNode object,
            Set<String> allowed,
            String prefix) {
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            String field = fields.next().getKey();
            if (!allowed.contains(field)) {
                throw unsupported(prefix + field);
            }
        }
    }

    private static String requiredFunctionName(JsonNode node, String parameter) {
        String value = requiredText(node, parameter, 64);
        if (!FUNCTION_NAME.matcher(value).matches()) {
            throw invalid("Function name is invalid.", parameter);
        }
        return value;
    }

    private static String requiredText(JsonNode node, String parameter, int maximumChars) {
        if (node == null || !node.isTextual() || node.textValue().isBlank()
                || node.textValue().length() > maximumChars) {
            throw invalid("A non-empty bounded string is required.", parameter);
        }
        return node.textValue();
    }

    private static void optionalText(JsonNode node, String parameter, int maximumChars) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isTextual() || node.textValue().length() > maximumChars) {
            throw invalid("A bounded string is required.", parameter);
        }
    }

    private static void boundedText(JsonNode node, String parameter) {
        if (!node.isTextual() || node.textValue().length() > MAX_TEXT_CHARS) {
            throw invalid("Text exceeds the allowed size.", parameter);
        }
    }

    private static boolean optionalBoolean(
            JsonNode node,
            String parameter,
            boolean fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isBoolean()) {
            throw invalid("A JSON boolean is required.", parameter);
        }
        return node.booleanValue();
    }

    private static Long optionalInteger(
            JsonNode node,
            String parameter,
            long minimum,
            long maximum) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalid("A JSON integer is required.", parameter);
        }
        long value = node.longValue();
        if (value < minimum || value > maximum) {
            throw invalid("Integer is outside the allowed range.", parameter);
        }
        return value;
    }

    private static void numberInRange(
            JsonNode node,
            String parameter,
            double minimum,
            double maximum) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isNumber() || !Double.isFinite(node.doubleValue())
                || node.doubleValue() < minimum || node.doubleValue() > maximum) {
            throw invalid("Number is outside the allowed range.", parameter);
        }
    }

    private static void optionalEnum(
            JsonNode node,
            String parameter,
            Set<String> allowed) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isTextual() || !allowed.contains(node.textValue())) {
            throw invalid("Value is unsupported.", parameter);
        }
    }

    private static ApiChatException invalid(String message, String parameter) {
        return ApiChatException.invalid(message, parameter);
    }

    private static ApiChatException unsupported(String parameter) {
        return ApiChatException.unsupported(parameter);
    }
}
