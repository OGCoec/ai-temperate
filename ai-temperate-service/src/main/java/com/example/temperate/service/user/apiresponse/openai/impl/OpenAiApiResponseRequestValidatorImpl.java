package com.example.temperate.service.user.apiresponse.openai.impl;

import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.service.user.apiresponse.openai.OpenAiApiResponseRequestValidation;
import com.example.temperate.service.user.apiresponse.openai.OpenAiApiResponseRequestValidator;
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
 * 该实现是来递归校验 OpenAI Responses 的文本、reasoning、function 和 JSON Schema 子集，并显式拒绝状态与多模态能力。
 */
@Service
public final class OpenAiApiResponseRequestValidatorImpl
        implements OpenAiApiResponseRequestValidator {

    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "model", "input", "instructions", "stream", "store",
            "max_output_tokens", "reasoning", "tools", "tool_choice",
            "parallel_tool_calls", "max_tool_calls", "include",
            "prompt_cache_key", "prompt_cache_retention", "service_tier",
            "text", "temperature", "top_p", "top_logprobs", "truncation",
            "safety_identifier", "user", "background",
            "previous_response_id", "conversation");
    private static final Set<String> MESSAGE_ROLES = Set.of(
            "developer", "system", "user", "assistant");
    private static final Set<String> REASONING_EFFORTS = Set.of(
            "none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra");
    private static final Set<String> REASONING_SUMMARIES = Set.of(
            "auto", "concise", "detailed");
    private static final Set<String> SERVICE_TIERS = Set.of(
            "auto", "default", "flex", "scale", "priority");
    private static final Set<String> VERBOSITIES = Set.of("low", "medium", "high");
    private static final Set<String> INCLUDES = Set.of(
            "reasoning.encrypted_content", "message.output_text.logprobs");
    private static final Pattern FUNCTION_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern CALL_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final int MAX_TEXT_CHARS = 262_144;
    private static final int MAX_SCHEMA_DEPTH = 16;
    private static final int MAX_SCHEMA_NODES = 2_000;

    private final ObjectMapper objectMapper;
    private final ApiKeyProperties properties;

    public OpenAiApiResponseRequestValidatorImpl(
            ObjectMapper objectMapper,
            ApiKeyProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public OpenAiApiResponseRequestValidation validate(ObjectNode request) {
        if (request == null) {
            throw invalid("Request is required.", null);
        }
        enforceBodySize(request);
        requireOnlyFields(request, TOP_LEVEL_FIELDS, "");
        String model = requiredText(request.get("model"), "model", 128);
        validateInput(request.get("input"));
        optionalText(request.get("instructions"), "instructions", MAX_TEXT_CHARS);
        boolean stream = optionalBoolean(request.get("stream"), "stream", false);
        validateStatelessFields(request);
        Long requestedMax = optionalPositiveInteger(
                request.get("max_output_tokens"), "max_output_tokens", 1);
        validateReasoning(request.get("reasoning"));
        Set<String> toolNames = validateTools(request.get("tools"));
        validateToolChoice(request.get("tool_choice"), toolNames);
        validateToolControls(request, toolNames);
        validateIncludes(request.get("include"));
        validateOptionalParameters(request);
        boolean structuredOutput = validateText(request.get("text"));

        // 无状态事实必须进入实际发送的 payload，避免上游默认 store=true 后返回与本地承诺矛盾的对象。
        ObjectNode normalized = request.deepCopy();
        normalized.put("store", false);
        return new OpenAiApiResponseRequestValidation(
                normalized, model, stream, requestedMax,
                !toolNames.isEmpty(), structuredOutput);
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

    private void validateInput(JsonNode input) {
        if (input == null || input.isNull()) {
            throw invalid("input is required.", "input");
        }
        if (input.isTextual()) {
            boundedText(input, "input");
            return;
        }
        if (!(input instanceof ArrayNode items) || items.isEmpty()
                || items.size() > properties.getRequest().getMaxMessages()) {
            throw invalid("input must be text or a bounded non-empty array.", "input");
        }
        Set<String> functionCallIds = new HashSet<>();
        Set<String> outputCallIds = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            String parameter = "input[" + index + "]";
            if (!(items.get(index) instanceof ObjectNode item)) {
                throw invalid("Input item must be an object.", parameter);
            }
            String type = item.path("type").isMissingNode()
                    ? "message" : requiredText(item.get("type"), parameter + ".type", 64);
            switch (type) {
                case "message" -> validateMessage(item, parameter);
                case "reasoning" -> validateReasoningItem(item, parameter);
                case "function_call" -> validateFunctionCall(
                        item, parameter, functionCallIds);
                case "function_call_output" -> validateFunctionOutput(
                        item, parameter, outputCallIds);
                default -> throw unsupported(parameter + ".type");
            }
        }
        if (!functionCallIds.containsAll(outputCallIds)) {
            throw invalid("Function output references an unknown call_id.", "input");
        }
    }

    private void validateMessage(ObjectNode item, String parameter) {
        requireOnlyFields(item, Set.of("type", "role", "content", "status", "id"),
                parameter + ".");
        String role = requiredText(item.get("role"), parameter + ".role", 32);
        if (!MESSAGE_ROLES.contains(role)) {
            throw unsupported(parameter + ".role");
        }
        optionalEnum(item.get("status"), parameter + ".status",
                Set.of("in_progress", "completed", "incomplete"));
        optionalText(item.get("id"), parameter + ".id", 128);
        validateTextContent(item.get("content"), parameter + ".content");
    }

    private void validateTextContent(JsonNode content, String parameter) {
        if (content != null && content.isTextual()) {
            boundedText(content, parameter);
            return;
        }
        if (!(content instanceof ArrayNode parts) || parts.isEmpty()) {
            throw invalid("Message content must be text or text parts.", parameter);
        }
        for (int index = 0; index < parts.size(); index++) {
            String partParameter = parameter + "[" + index + "]";
            if (!(parts.get(index) instanceof ObjectNode part)) {
                throw invalid("Content part must be an object.", partParameter);
            }
            String type = requiredText(part.get("type"), partParameter + ".type", 64);
            if (!Set.of("input_text", "output_text").contains(type)) {
                throw unsupported(partParameter + ".type");
            }
            requireOnlyFields(part, Set.of("type", "text"), partParameter + ".");
            requiredText(part.get("text"), partParameter + ".text", MAX_TEXT_CHARS);
        }
    }

    private void validateReasoningItem(ObjectNode item, String parameter) {
        requireOnlyFields(item,
                Set.of("type", "id", "encrypted_content", "summary", "status"),
                parameter + ".");
        optionalText(item.get("id"), parameter + ".id", 128);
        optionalText(item.get("encrypted_content"),
                parameter + ".encrypted_content", MAX_TEXT_CHARS);
        optionalEnum(item.get("status"), parameter + ".status",
                Set.of("in_progress", "completed", "incomplete"));
        JsonNode summary = item.get("summary");
        if (summary == null || summary.isNull()) {
            return;
        }
        if (!(summary instanceof ArrayNode parts)) {
            throw invalid("Reasoning summary must be an array.", parameter + ".summary");
        }
        for (int index = 0; index < parts.size(); index++) {
            String partParameter = parameter + ".summary[" + index + "]";
            if (!(parts.get(index) instanceof ObjectNode part)) {
                throw invalid("Reasoning summary part must be an object.", partParameter);
            }
            requireOnlyFields(part, Set.of("type", "text"), partParameter + ".");
            if (!"summary_text".equals(requiredText(
                    part.get("type"), partParameter + ".type", 64))) {
                throw unsupported(partParameter + ".type");
            }
            requiredText(part.get("text"), partParameter + ".text", MAX_TEXT_CHARS);
        }
    }

    private void validateFunctionCall(
            ObjectNode item,
            String parameter,
            Set<String> callIds) {
        requireOnlyFields(item,
                Set.of("type", "id", "call_id", "name", "arguments", "status"),
                parameter + ".");
        String callId = requiredCallId(item.get("call_id"), parameter + ".call_id");
        if (!callIds.add(callId)) {
            throw invalid("Function call_id must be unique.", parameter + ".call_id");
        }
        optionalText(item.get("id"), parameter + ".id", 128);
        requiredFunctionName(item.get("name"), parameter + ".name");
        requiredText(item.get("arguments"), parameter + ".arguments", MAX_TEXT_CHARS);
        optionalEnum(item.get("status"), parameter + ".status",
                Set.of("in_progress", "completed", "incomplete"));
    }

    private void validateFunctionOutput(
            ObjectNode item,
            String parameter,
            Set<String> outputIds) {
        requireOnlyFields(item, Set.of("type", "id", "call_id", "output", "status"),
                parameter + ".");
        String callId = requiredCallId(item.get("call_id"), parameter + ".call_id");
        if (!outputIds.add(callId)) {
            throw invalid("Function output call_id must be unique.",
                    parameter + ".call_id");
        }
        optionalText(item.get("id"), parameter + ".id", 128);
        requiredText(item.get("output"), parameter + ".output", MAX_TEXT_CHARS);
        optionalEnum(item.get("status"), parameter + ".status",
                Set.of("in_progress", "completed", "incomplete"));
    }

    private void validateReasoning(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!(node instanceof ObjectNode reasoning)) {
            throw invalid("reasoning must be an object.", "reasoning");
        }
        requireOnlyFields(reasoning, Set.of("effort", "summary"), "reasoning.");
        optionalEnum(reasoning.get("effort"), "reasoning.effort", REASONING_EFFORTS);
        optionalEnum(reasoning.get("summary"), "reasoning.summary", REASONING_SUMMARIES);
    }

    private Set<String> validateTools(JsonNode node) {
        if (node == null || node.isNull()) {
            return Set.of();
        }
        if (!(node instanceof ArrayNode tools)
                || tools.size() > properties.getRequest().getMaxTools()) {
            throw invalid("tools must be a bounded array.", "tools");
        }
        validateSerializedToolBudget(tools);
        Set<String> names = new HashSet<>();
        for (int index = 0; index < tools.size(); index++) {
            String parameter = "tools[" + index + "]";
            if (!(tools.get(index) instanceof ObjectNode tool)) {
                throw invalid("Tool must be an object.", parameter);
            }
            requireOnlyFields(tool,
                    Set.of("type", "name", "description", "parameters", "strict"),
                    parameter + ".");
            if (!"function".equals(requiredText(tool.get("type"),
                    parameter + ".type", 32))) {
                throw unsupported(parameter + ".type");
            }
            String name = requiredFunctionName(tool.get("name"), parameter + ".name");
            if (!names.add(name)) {
                throw invalid("Function tool names must be unique.", parameter + ".name");
            }
            JsonNode description = tool.get("description");
            optionalText(description, parameter + ".description", MAX_TEXT_CHARS);
            if (description != null && description.isTextual()
                    && description.textValue().getBytes(StandardCharsets.UTF_8).length
                    > properties.getRequest().getMaxToolDescriptionBytes()) {
                throw invalid("Function description exceeds the allowed UTF-8 size.",
                        parameter + ".description");
            }
            validateSchema(tool.get("parameters"), 0, new int[]{0},
                    parameter + ".parameters");
            optionalBoolean(tool.get("strict"), parameter + ".strict", false);
        }
        return Set.copyOf(names);
    }

    private void validateToolChoice(JsonNode node, Set<String> toolNames) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            if (!Set.of("none", "auto", "required").contains(node.textValue())) {
                throw invalid("tool_choice is invalid.", "tool_choice");
            }
        } else if (node instanceof ObjectNode choice) {
            requireOnlyFields(choice, Set.of("type", "name"), "tool_choice.");
            if (!"function".equals(requiredText(
                    choice.get("type"), "tool_choice.type", 32))) {
                throw unsupported("tool_choice.type");
            }
            String name = requiredFunctionName(choice.get("name"), "tool_choice.name");
            if (!toolNames.contains(name)) {
                throw invalid("tool_choice names an undeclared function.", "tool_choice.name");
            }
        } else {
            throw invalid("tool_choice has an invalid JSON type.", "tool_choice");
        }
        if (toolNames.isEmpty() && !"none".equals(node.asText())) {
            throw invalid("tool_choice requires function tools.", "tool_choice");
        }
    }

    private void validateToolControls(ObjectNode request, Set<String> toolNames) {
        JsonNode parallel = request.get("parallel_tool_calls");
        if (parallel != null && !parallel.isNull()) {
            boolean enabled = optionalBoolean(
                    parallel, "parallel_tool_calls", false);
            if (enabled && toolNames.isEmpty()) {
                throw invalid("parallel_tool_calls requires function tools.",
                        "parallel_tool_calls");
            }
        }
        JsonNode maximum = request.get("max_tool_calls");
        if (maximum != null && !maximum.isNull()) {
            optionalPositiveInteger(maximum, "max_tool_calls", 1);
            if (toolNames.isEmpty()) {
                throw invalid("max_tool_calls requires function tools.", "max_tool_calls");
            }
        }
    }

    private void validateIncludes(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!(node instanceof ArrayNode values) || values.size() > 16) {
            throw invalid("include must be a bounded array.", "include");
        }
        for (int index = 0; index < values.size(); index++) {
            String parameter = "include[" + index + "]";
            String value = requiredText(values.get(index), parameter, 128);
            if (!INCLUDES.contains(value)) {
                throw unsupported(parameter);
            }
        }
    }

    private boolean validateText(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (!(node instanceof ObjectNode text)) {
            throw invalid("text must be an object.", "text");
        }
        requireOnlyFields(text, Set.of("format", "verbosity"), "text.");
        optionalEnum(text.get("verbosity"), "text.verbosity", VERBOSITIES);
        JsonNode formatNode = text.get("format");
        if (formatNode == null || formatNode.isNull()) {
            return false;
        }
        if (!(formatNode instanceof ObjectNode format)) {
            throw invalid("text.format must be an object.", "text.format");
        }
        String type = requiredText(format.get("type"), "text.format.type", 32);
        switch (type) {
            case "text", "json_object" -> requireOnlyFields(
                    format, Set.of("type"), "text.format.");
            case "json_schema" -> {
                requireOnlyFields(format, Set.of("type", "name", "description", "schema", "strict"),
                        "text.format.");
                requiredFunctionName(format.get("name"), "text.format.name");
                optionalText(format.get("description"),
                        "text.format.description", MAX_TEXT_CHARS);
                validateSchema(format.get("schema"), 0, new int[]{0}, "text.format.schema");
                optionalBoolean(format.get("strict"), "text.format.strict", false);
            }
            default -> throw unsupported("text.format.type");
        }
        return !"text".equals(type);
    }

    private void validateOptionalParameters(ObjectNode request) {
        numberInRange(request.get("temperature"), "temperature", 0, 2);
        numberInRange(request.get("top_p"), "top_p", 0, 1);
        optionalPositiveInteger(request.get("top_logprobs"), "top_logprobs", 0);
        optionalEnum(request.get("service_tier"), "service_tier", SERVICE_TIERS);
        optionalEnum(request.get("truncation"), "truncation", Set.of("auto", "disabled"));
        optionalText(request.get("safety_identifier"), "safety_identifier", 512);
        optionalText(request.get("user"), "user", 512);
        optionalText(request.get("prompt_cache_key"), "prompt_cache_key", 256);
        optionalEnum(request.get("prompt_cache_retention"),
                "prompt_cache_retention", Set.of("in-memory", "24h"));
    }

    private void validateStatelessFields(ObjectNode request) {
        validateFalseOrMissing(request.get("store"), "store");
        validateFalseOrMissing(request.get("background"), "background");
        if (request.hasNonNull("previous_response_id")) {
            throw unsupported("previous_response_id");
        }
        if (request.hasNonNull("conversation")) {
            throw unsupported("conversation");
        }
    }

    private static void validateFalseOrMissing(JsonNode node, String parameter) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isBoolean()) {
            throw invalid(parameter + " must be a JSON boolean.", parameter);
        }
        if (node.booleanValue()) {
            throw unsupported(parameter);
        }
    }

    private void validateSerializedToolBudget(JsonNode tools) {
        try {
            if (objectMapper.writeValueAsBytes(tools).length
                    > properties.getRequest().getMaxToolDefinitionsBytes()) {
                throw invalid("Tool definitions exceed the allowed UTF-8 size.", "tools");
            }
        } catch (JsonProcessingException failure) {
            throw invalid("Tool definitions cannot be encoded.", "tools");
        }
    }

    private static void validateSchema(
            JsonNode node,
            int depth,
            int[] nodes,
            String parameter) {
        if (node == null || node.isNull()) {
            throw invalid("JSON Schema is required.", parameter);
        }
        if (!node.isContainerNode()) {
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

    private static String requiredCallId(JsonNode node, String parameter) {
        String value = requiredText(node, parameter, 128);
        if (!CALL_ID.matcher(value).matches()) {
            throw invalid("call_id is invalid.", parameter);
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

    private static Long optionalPositiveInteger(
            JsonNode node,
            String parameter,
            long minimum) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()
                || node.longValue() < minimum) {
            throw invalid("A bounded JSON integer is required.", parameter);
        }
        return node.longValue();
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
