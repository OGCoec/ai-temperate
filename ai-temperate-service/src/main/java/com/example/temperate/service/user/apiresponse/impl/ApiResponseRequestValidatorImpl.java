package com.example.temperate.service.user.apiresponse.impl;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.service.user.apiresponse.ApiResponseRequest;
import com.example.temperate.service.user.apiresponse.ApiResponseRequest.Tool;
import com.example.temperate.service.user.apiresponse.ApiResponseRequestValidator;
import com.example.temperate.service.user.apiresponse.ValidatedApiResponseRequest;
import com.example.temperate.service.user.openaicompatibility.LooseOpenAiRequestContext;
import com.example.temperate.service.user.openaicompatibility.LooseOpenAiRequestNormalization;
import com.example.temperate.service.user.openaicompatibility.LooseOpenAiRequestNormalizerRegistry;
import com.example.temperate.service.user.openaicompatibility.OpenAiCompatibilityProtocol;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 该实现是来在开关开启时把所有厂商交给 Responses 宽松规范化策略，关闭时继续执行旧白名单语义和关系校验。
 */
@Service
public final class ApiResponseRequestValidatorImpl
        implements ApiResponseRequestValidator {

    private static final Pattern FUNCTION_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern CALL_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final Set<String> ROLES = Set.of(
            "developer", "system", "user", "assistant");
    private static final Set<String> REASONING_EFFORTS = Set.of(
            "none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra");
    private static final Set<String> REASONING_SUMMARIES = Set.of(
            "auto", "concise", "detailed");
    private static final Set<String> SERVICE_TIERS = Set.of(
            "auto", "default", "flex", "scale", "priority");
    private static final Set<String> VERBOSITIES = Set.of("low", "medium", "high");
    private static final Set<String> INPUT_ITEM_FIELDS = Set.of(
            "type", "role", "content", "id", "status", "call_id", "name",
            "arguments", "output", "encrypted_content", "summary");
    private static final Set<String> CONTENT_PART_FIELDS = Set.of("type", "text");
    private static final Set<String> SUMMARY_PART_FIELDS = Set.of("type", "text");
    private static final Set<String> TOOL_CHOICE_FIELDS = Set.of("type", "name");
    private static final int MAX_TEXT_CHARS = 262_144;
    private static final int MAX_PROMPT_CACHE_KEY_BYTES = 256;
    private static final int MAX_SCHEMA_DEPTH = 16;
    private static final int MAX_SCHEMA_NODES = 2_000;

    private final AiModelCacheService modelCacheService;
    private final ApiKeyProperties properties;
    private final ObjectMapper objectMapper;
    private final LooseOpenAiRequestNormalizerRegistry normalizerRegistry;

    public ApiResponseRequestValidatorImpl(
            AiModelCacheService modelCacheService,
            ApiKeyProperties properties,
            ObjectMapper objectMapper,
            LooseOpenAiRequestNormalizerRegistry normalizerRegistry) {
        this.modelCacheService = Objects.requireNonNull(modelCacheService);
        this.properties = Objects.requireNonNull(properties);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.normalizerRegistry = Objects.requireNonNull(normalizerRegistry);
    }

    @Override
    public ValidatedApiResponseRequest validate(
            ApiKeyPrincipal principal,
            ObjectNode request) {
        if (principal == null || request == null) {
            throw invalid("Request is required.", null);
        }
        JsonNode modelNode = request.get("model");
        if (modelNode == null || !modelNode.isTextual()
                || modelNode.textValue().isBlank()
                || modelNode.textValue().length() > 128) {
            throw invalid("Model is required.", "model");
        }
        AiModelCacheEntry model = findModel(modelNode.textValue());
        if (properties.getOpenAiCompatibility().isEnabled()) {
            validateCompatibleModelAccess(principal, model);
            LooseOpenAiRequestNormalization normalized = normalizerRegistry
                    .getRequired(OpenAiCompatibilityProtocol.RESPONSES)
                    .normalize(new LooseOpenAiRequestContext(
                            request, model, OpenAiCompatibilityProtocol.RESPONSES));
            long estimatedInput = estimateRawInputTokens(normalized.normalizedPayload());
            if (estimatedInput > model.contextWindowTokens()
                    - normalized.effectiveMaxOutputTokens()) {
                throw new ApiChatException(
                        ApiChatErrorCode.CONTEXT_LENGTH_EXCEEDED,
                        "The request exceeds the model context window.",
                        "input");
            }
            return ValidatedApiResponseRequest.compatible(
                    model, normalized.effectiveMaxOutputTokens(), estimatedInput,
                    normalized.stream(), normalized.normalizedPayload(),
                    normalized.payloadMode(), normalized.droppedFieldCount());
        }
        try {
            return validate(principal,
                    objectMapper.treeToValue(request, ApiResponseRequest.class));
        } catch (JsonProcessingException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof ApiChatException controlled) {
                throw controlled;
            }
            throw invalid("The request body contains an invalid field type.", null);
        }
    }

    private void validateCompatibleModelAccess(
            ApiKeyPrincipal principal,
            AiModelCacheEntry model) {
        if (!model.capabilities().contains(AiModelCapabilityCode.RESPONSES)
                || model.contextWindowTokens() <= 0
                || model.maxOutputTokens() <= 0) {
            throw new ApiChatException(
                    ApiChatErrorCode.MODEL_NOT_FOUND,
                    "The requested model is unavailable.",
                    "model");
        }
        if (!principal.modelIds().contains(model.id())) {
            throw new ApiChatException(
                    ApiChatErrorCode.MODEL_NOT_ALLOWED,
                    "The API Key is not authorized for this model.",
                    "model");
        }
    }

    private long estimateRawInputTokens(ObjectNode payload) {
        try {
            return Math.max(1L,
                    Math.ceilDiv(5L + objectMapper.writeValueAsBytes(payload).length, 3L));
        } catch (JsonProcessingException failure) {
            throw invalid("Request body cannot be encoded.", null);
        }
    }

    @Override
    public ValidatedApiResponseRequest validate(
            ApiKeyPrincipal principal,
            ApiResponseRequest request) {
        if (principal == null || request == null) {
            throw invalid("Request is required.", null);
        }
        enforceBodySize(request);
        String requestedModel = requiredText(request.model(), "model", 128);
        AiModelCacheEntry model = findModel(requestedModel);
        if (!model.capabilities().contains(AiModelCapabilityCode.RESPONSES)
                || model.contextWindowTokens() <= 0
                || model.maxOutputTokens() <= 0) {
            throw new ApiChatException(
                    ApiChatErrorCode.MODEL_NOT_FOUND,
                    "The requested model is unavailable.",
                    "model");
        }
        if (!principal.modelIds().contains(model.id())) {
            throw new ApiChatException(
                    ApiChatErrorCode.MODEL_NOT_ALLOWED,
                    "The API Key is not authorized for this model.",
                    "model");
        }

        boolean stream = optionalBoolean(request.stream(), "stream", false);
        validateStore(request.store());
        validateInstructions(request.instructions());
        validateInput(request.input());
        validateReasoning(request.reasoning());
        Set<String> toolNames = validateTools(request.tools());
        validateToolChoice(request.toolChoice(), toolNames);
        if (!isMissingOrNull(request.parallelToolCalls())) {
            optionalBoolean(request.parallelToolCalls(), "parallel_tool_calls", false);
        }
        validateInclude(request.include());
        validatePromptCacheKey(request.promptCacheKey());
        optionalEnum(request.serviceTier(), "service_tier", SERVICE_TIERS);
        validateText(request.text());
        numberInRange(request.temperature(), "temperature", 0.0D, 2.0D);
        numberInRange(request.topP(), "top_p", 0.0D, 1.0D);

        // OpenAI 客户端可能把未设置的可选值序列化为 JSON null；它必须与字段缺省具有相同语义。
        long effectiveMax = isMissingOrNull(request.maxOutputTokens())
                ? model.maxOutputTokens()
                : Math.min(integerAtLeast(
                        request.maxOutputTokens(), "max_output_tokens", 16L),
                        model.maxOutputTokens());
        long estimatedInput = estimateInputTokens(request);
        if (estimatedInput > model.contextWindowTokens() - effectiveMax) {
            throw new ApiChatException(
                    ApiChatErrorCode.CONTEXT_LENGTH_EXCEEDED,
                    "The request exceeds the model context window.",
                    "input");
        }
        return new ValidatedApiResponseRequest(
                request, model, effectiveMax, estimatedInput, stream);
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

    private void enforceBodySize(ApiResponseRequest request) {
        try {
            if (objectMapper.writeValueAsBytes(request).length
                    > properties.getRequest().getMaxBodyBytes()) {
                throw invalid("Request body exceeds 1 MiB.", null);
            }
        } catch (JsonProcessingException exception) {
            throw invalid("Request body cannot be encoded.", null);
        }
    }

    private void validateStore(JsonNode store) {
        if (isMissingOrNull(store)) {
            return;
        }
        if (!store.isBoolean()) {
            throw invalid("store must be a JSON boolean.", "store");
        }
        if (store.booleanValue()) {
            throw invalid("Only store=false is supported.", "store");
        }
    }

    private void validateInstructions(JsonNode instructions) {
        if (instructions == null || instructions.isNull()) {
            return;
        }
        requiredText(instructions, "instructions", MAX_TEXT_CHARS);
    }

    private void validateInput(JsonNode input) {
        if (input == null || input.isNull()) {
            throw invalid("input is required.", "input");
        }
        if (input.isTextual()) {
            boundedText(input, "input");
            return;
        }
        if (!input.isArray() || input.isEmpty()
                || input.size() > properties.getRequest().getMaxMessages()) {
            throw invalid("input must be text or contain 1 to 256 items.", "input");
        }
        Set<String> calls = new HashSet<>();
        Set<String> outputs = new HashSet<>();
        for (int index = 0; index < input.size(); index++) {
            validateInputItem(input.get(index), index, calls, outputs);
        }
    }

    private void validateInputItem(
            JsonNode item,
            int index,
            Set<String> calls,
            Set<String> outputs) {
        String parameter = "input[" + index + "]";
        if (!(item instanceof ObjectNode object)) {
            throw invalid("Each input item must be an object.", parameter);
        }
        requireOnlyFields(object, INPUT_ITEM_FIELDS, parameter);
        JsonNode typeNode = object.get("type");
        String type = typeNode == null && object.has("role")
                ? "message" : requiredText(typeNode, parameter + ".type", 64);
        switch (type) {
            case "message" -> validateMessageItem(object, parameter);
            case "function_call" -> validateFunctionCallItem(object, parameter, calls);
            case "function_call_output" ->
                    validateFunctionOutputItem(object, parameter, calls, outputs);
            case "reasoning" -> validateReasoningItem(object, parameter);
            default -> throw invalid("Input item type is unsupported.", parameter + ".type");
        }
    }

    private void validateMessageItem(ObjectNode item, String parameter) {
        requireFieldsAbsent(item, parameter,
                "call_id", "name", "arguments", "output", "encrypted_content", "summary");
        String role = requiredText(item.get("role"), parameter + ".role", 32);
        if (!ROLES.contains(role)) {
            throw invalid("Message role is unsupported.", parameter + ".role");
        }
        validateTextContent(item.get("content"), parameter + ".content");
        validateOptionalMetadata(item, parameter);
    }

    private void validateFunctionCallItem(
            ObjectNode item,
            String parameter,
            Set<String> calls) {
        requireFieldsAbsent(item, parameter,
                "role", "content", "output", "encrypted_content", "summary");
        String callId = requiredText(item.get("call_id"), parameter + ".call_id", 128);
        if (!CALL_ID.matcher(callId).matches() || !calls.add(callId)) {
            throw invalid("function_call call_id must be unique.", parameter + ".call_id");
        }
        String name = requiredText(item.get("name"), parameter + ".name", 64);
        if (!FUNCTION_NAME.matcher(name).matches()) {
            throw invalid("Function name is invalid.", parameter + ".name");
        }
        requiredText(item.get("arguments"), parameter + ".arguments", MAX_TEXT_CHARS);
        validateOptionalMetadata(item, parameter);
    }

    private void validateFunctionOutputItem(
            ObjectNode item,
            String parameter,
            Set<String> calls,
            Set<String> outputs) {
        requireFieldsAbsent(item, parameter,
                "role", "content", "name", "arguments", "encrypted_content", "summary");
        String callId = requiredText(item.get("call_id"), parameter + ".call_id", 128);
        if (!CALL_ID.matcher(callId).matches()
                || !calls.contains(callId)
                || !outputs.add(callId)) {
            throw invalid(
                    "function_call_output must uniquely reference an earlier function_call.",
                    parameter + ".call_id");
        }
        validateTextContent(item.get("output"), parameter + ".output");
        validateOptionalMetadata(item, parameter);
    }

    private void validateReasoningItem(ObjectNode item, String parameter) {
        requireFieldsAbsent(item, parameter,
                "role", "content", "call_id", "name", "arguments", "output");
        requiredText(
                item.get("encrypted_content"),
                parameter + ".encrypted_content",
                MAX_TEXT_CHARS);
        JsonNode summary = item.get("summary");
        if (!isMissingOrNull(summary)) {
            if (!summary.isArray()) {
                throw invalid("reasoning summary must be an array.", parameter + ".summary");
            }
            for (int index = 0; index < summary.size(); index++) {
                JsonNode part = summary.get(index);
                if (!(part instanceof ObjectNode object)) {
                    throw invalid("Reasoning summary part is invalid.", parameter + ".summary");
                }
                requireOnlyFields(object, SUMMARY_PART_FIELDS, parameter + ".summary");
                String type = requiredText(object.get("type"), parameter + ".summary.type", 64);
                if (!"summary_text".equals(type)) {
                    throw invalid("Reasoning summary part is unsupported.", parameter + ".summary.type");
                }
                boundedText(object.get("text"), parameter + ".summary.text");
            }
        }
        validateOptionalMetadata(item, parameter);
    }

    private void validateOptionalMetadata(ObjectNode item, String parameter) {
        JsonNode id = item.get("id");
        if (!isMissingOrNull(id)) {
            requiredText(id, parameter + ".id", 128);
        }
        JsonNode status = item.get("status");
        if (!isMissingOrNull(status)) {
            String value = requiredText(status, parameter + ".status", 32);
            if (!Set.of("in_progress", "completed", "incomplete").contains(value)) {
                throw invalid("Input item status is unsupported.", parameter + ".status");
            }
        }
    }

    private void validateTextContent(JsonNode content, String parameter) {
        if (content == null || content.isNull()) {
            throw invalid("Text content is required.", parameter);
        }
        if (content.isTextual()) {
            boundedText(content, parameter);
            return;
        }
        if (!content.isArray() || content.isEmpty()) {
            throw invalid("Text content must be text or a non-empty parts array.", parameter);
        }
        for (int index = 0; index < content.size(); index++) {
            JsonNode part = content.get(index);
            if (!(part instanceof ObjectNode object)) {
                throw invalid("Content part must be an object.", parameter);
            }
            requireOnlyFields(object, CONTENT_PART_FIELDS, parameter);
            String type = requiredText(object.get("type"), parameter + ".type", 32);
            if (!Set.of("input_text", "output_text").contains(type)) {
                throw invalid("Only text content parts are supported.", parameter + ".type");
            }
            boundedText(object.get("text"), parameter + ".text");
        }
    }

    private void validateReasoning(ApiResponseRequest.Reasoning reasoning) {
        if (reasoning == null) {
            return;
        }
        optionalEnum(reasoning.effort(), "reasoning.effort", REASONING_EFFORTS);
        optionalEnum(reasoning.summary(), "reasoning.summary", REASONING_SUMMARIES);
    }

    private Set<String> validateTools(List<Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return Set.of();
        }
        if (tools.size() > properties.getRequest().getMaxTools()) {
            throw invalid("tools has an invalid size.", "tools");
        }
        Set<String> names = new HashSet<>();
        for (int index = 0; index < tools.size(); index++) {
            Tool tool = tools.get(index);
            String parameter = "tools[" + index + "]";
            if (tool == null || !"function".equals(
                    requiredText(tool.type(), parameter + ".type", 32))) {
                throw invalid("Only type=function tools are supported.", parameter);
            }
            String toolName = requiredText(tool.name(), parameter + ".name", 64);
            if (!FUNCTION_NAME.matcher(toolName).matches() || !names.add(toolName)) {
                throw invalid("Function tool name is invalid or duplicated.", parameter + ".name");
            }
            if (!isMissingOrNull(tool.description())
                    && requiredText(
                            tool.description(), parameter + ".description", MAX_TEXT_CHARS)
                    .getBytes(StandardCharsets.UTF_8).length
                    > properties.getRequest().getMaxToolDescriptionBytes()) {
                throw invalid("Function tool description is too large.", parameter + ".description");
            }
            if (tool.parameters() == null || !tool.parameters().isObject()) {
                throw invalid("Function parameters must be a JSON object.", parameter + ".parameters");
            }
            int[] nodes = {0};
            validateSchema(tool.parameters(), 1, nodes, parameter + ".parameters");
            if (!isMissingOrNull(tool.strict()) && !tool.strict().isBoolean()) {
                throw invalid("strict must be a JSON boolean.", parameter + ".strict");
            }
        }
        try {
            if (objectMapper.writeValueAsBytes(tools).length
                    > properties.getRequest().getMaxToolDefinitionsBytes()) {
                throw invalid("Tool definitions exceed the allowed UTF-8 size.", "tools");
            }
        } catch (JsonProcessingException exception) {
            throw invalid("Tools cannot be encoded.", "tools");
        }
        return Set.copyOf(names);
    }

    private void validateSchema(JsonNode node, int depth, int[] nodes, String parameter) {
        nodes[0]++;
        if (depth > MAX_SCHEMA_DEPTH || nodes[0] > MAX_SCHEMA_NODES) {
            throw invalid("Function JSON Schema is too complex.", parameter);
        }
        if (node.isContainerNode()) {
            node.elements().forEachRemaining(child ->
                    validateSchema(child, depth + 1, nodes, parameter));
        } else if (!node.isValueNode()) {
            throw invalid("Function JSON Schema is invalid.", parameter);
        }
    }

    private void validateToolChoice(JsonNode choice, Set<String> toolNames) {
        if (isMissingOrNull(choice)) {
            return;
        }
        if (choice.isTextual()) {
            String value = choice.textValue();
            if (!Set.of("none", "auto", "required").contains(value)) {
                throw invalid("tool_choice is unsupported.", "tool_choice");
            }
            if ("required".equals(value)) {
                requireTools(toolNames, "tool_choice");
            }
            return;
        }
        if (!(choice instanceof ObjectNode object)) {
            throw invalid("tool_choice has an invalid type.", "tool_choice");
        }
        requireTools(toolNames, "tool_choice");
        requireOnlyFields(object, TOOL_CHOICE_FIELDS, "tool_choice");
        if (!"function".equals(requiredText(object.get("type"), "tool_choice.type", 32))) {
            throw invalid("Only function tool_choice is supported.", "tool_choice.type");
        }
        String selected = requiredText(object.get("name"), "tool_choice.name", 64);
        if (!toolNames.contains(selected)) {
            throw invalid("tool_choice references an undeclared function.", "tool_choice.name");
        }
    }

    private void requireTools(Set<String> toolNames, String parameter) {
        if (toolNames.isEmpty()) {
            throw invalid(parameter + " requires tools.", parameter);
        }
    }

    private void validateInclude(List<JsonNode> include) {
        if (include == null || include.isEmpty()) {
            return;
        }
        if (include.size() != 1
                || !"reasoning.encrypted_content".equals(
                requiredText(include.get(0), "include", 64))) {
            throw invalid(
                    "Only reasoning.encrypted_content may be included.",
                    "include");
        }
    }

    private void validatePromptCacheKey(JsonNode key) {
        if (key == null || key.isNull()) {
            return;
        }
        String value = requiredText(key, "prompt_cache_key", MAX_TEXT_CHARS);
        if (value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_PROMPT_CACHE_KEY_BYTES) {
            throw invalid("prompt_cache_key is invalid.", "prompt_cache_key");
        }
    }

    private void validateText(ApiResponseRequest.Text text) {
        if (text == null) {
            return;
        }
        if (text.format() != null && !"text".equals(requiredText(
                text.format().type(), "text.format.type", 32))) {
            throw invalid("Only plain text output is supported.", "text.format.type");
        }
        optionalEnum(text.verbosity(), "text.verbosity", VERBOSITIES);
    }

    private long estimateInputTokens(ApiResponseRequest request) {
        try {
            long bytes = objectMapper.writeValueAsBytes(request).length;
            long itemOverhead = request.input() != null && request.input().isArray()
                    ? Math.multiplyExact(request.input().size(), 8L) : 8L;
            long toolOverhead = request.tools() == null
                    ? 0L : Math.multiplyExact(request.tools().size(), 16L);
            // 三字节折算对中文、工具参数和加密 reasoning 保持保守，固定开销覆盖角色与协议标记。
            return Math.addExact(Math.ceilDiv(bytes, 3L),
                    Math.addExact(itemOverhead, toolOverhead));
        } catch (JsonProcessingException | ArithmeticException exception) {
            throw invalid("Request is too large to estimate safely.", "input");
        }
    }

    private static boolean optionalBoolean(JsonNode node, String parameter, boolean fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isBoolean()) {
            throw invalid(parameter + " must be a JSON boolean.", parameter);
        }
        return node.booleanValue();
    }

    private static long integerAtLeast(JsonNode node, String parameter, long minimum) {
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalid(
                    parameter + " must be an integer.",
                    parameter,
                    ApiChatException.ValidationReason.WRONG_JSON_TYPE);
        }
        long value = node.longValue();
        if (value < minimum) {
            throw invalid(
                    parameter + " is below the supported minimum.",
                    parameter,
                    ApiChatException.ValidationReason.BELOW_MINIMUM);
        }
        return value;
    }

    private static boolean isMissingOrNull(JsonNode node) {
        return node == null || node.isNull();
    }

    private static void numberInRange(
            JsonNode node,
            String parameter,
            double minimum,
            double maximum) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isNumber()) {
            throw invalid(parameter + " must be a JSON number.", parameter);
        }
        double value = node.doubleValue();
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw invalid(parameter + " is outside the supported range.", parameter);
        }
    }

    private static void optionalEnum(JsonNode node, String parameter, Set<String> allowed) {
        if (node == null || node.isNull()) {
            return;
        }
        String value = requiredText(node, parameter, 64);
        if (!allowed.contains(value)) {
            throw invalid(parameter + " is unsupported.", parameter);
        }
    }

    private static String requiredText(JsonNode node, String parameter, int maximumChars) {
        if (node == null || !node.isTextual()) {
            throw invalid(parameter + " must be a JSON string.", parameter);
        }
        String value = node.textValue();
        if (value.isBlank() || value.length() > maximumChars) {
            throw invalid(parameter + " is invalid.", parameter);
        }
        return value;
    }

    private static void boundedText(JsonNode node, String parameter) {
        requiredText(node, parameter, MAX_TEXT_CHARS);
    }

    private static void requireOnlyFields(
            ObjectNode object,
            Set<String> allowed,
            String parameter) {
        Iterator<String> fields = object.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) {
                throw invalid("Object contains an unsupported field.", parameter);
            }
        }
    }

    private static void requireFieldsAbsent(
            ObjectNode object,
            String parameter,
            String... forbidden) {
        for (String field : forbidden) {
            if (object.has(field)) {
                throw invalid("Input item contains an incompatible field.", parameter + "." + field);
            }
        }
    }

    private static ApiChatException invalid(String message, String parameter) {
        return new ApiChatException(ApiChatErrorCode.INVALID_REQUEST, message, parameter);
    }

    private static ApiChatException invalid(
            String message,
            String parameter,
            ApiChatException.ValidationReason validationReason) {
        return ApiChatException.invalid(message, parameter, validationReason);
    }
}
