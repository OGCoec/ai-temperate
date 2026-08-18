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
import com.example.temperate.service.user.apichat.diagnostic.ApiChatDiagnosticParameter;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatValidationFailureRule;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatValidationRejection;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * 该实现是来在开关开启时把所有厂商交给 Chat 宽松规范化策略，关闭时保留旧严格校验，并让同一 Token 上限进入上下文、预扣与上游请求。
 */
@Service
public final class ApiChatRequestValidatorImpl implements ApiChatRequestValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ApiChatRequestValidatorImpl.class);
    private static final String DIAGNOSTIC_SCHEMA = "chat-diag-v1";
    private static final Pattern FUNCTION_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Set<String> ROLES = Set.of("system", "user", "assistant", "tool");
    private static final Set<String> REASONING_EFFORTS = Set.of(
            "none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra");
    private static final Set<String> SERVICE_TIERS = Set.of(
            "auto", "default", "flex", "scale", "priority");
    private static final int MAX_MESSAGE_TEXT_CHARS = 262_144;
    private static final int MAX_PROMPT_CACHE_KEY_BYTES = 256;
    private static final int MAX_SCHEMA_DEPTH = 16;
    private static final int MAX_SCHEMA_NODES = 2_000;

    private final AiModelCacheService modelCacheService;
    private final ApiKeyProperties properties;
    private final ObjectMapper objectMapper;
    private final LooseOpenAiRequestNormalizerRegistry normalizerRegistry;

    public ApiChatRequestValidatorImpl(
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
    public ValidatedApiChatRequest validate(
            ApiKeyPrincipal principal,
            ObjectNode request) {
        if (principal == null || request == null) {
            throw ApiChatException.invalid("Request is required.", null);
        }
        JsonNode modelNode = request.get("model");
        if (modelNode == null || !modelNode.isTextual()
                || modelNode.textValue().isBlank()
                || modelNode.textValue().length() > 128) {
            throw ApiChatException.invalid("Model is required.", "model");
        }
        AiModelCacheEntry model = findModel(modelNode.textValue());
        if (properties.getOpenAiCompatibility().isEnabled()) {
            validateCompatibleModelAccess(principal, model);
            LooseOpenAiRequestNormalization normalized = normalizerRegistry
                    .getRequired(OpenAiCompatibilityProtocol.CHAT_COMPLETIONS)
                    .normalize(new LooseOpenAiRequestContext(
                            request, model, OpenAiCompatibilityProtocol.CHAT_COMPLETIONS));
            long estimatedPrompt = estimateRawPromptTokens(normalized.normalizedPayload());
            if (estimatedPrompt > model.contextWindowTokens()
                    - normalized.effectiveMaxOutputTokens()) {
                throw new ApiChatException(
                        ApiChatErrorCode.CONTEXT_LENGTH_EXCEEDED,
                        "The request exceeds the model context window.",
                        "messages");
            }
            return ValidatedApiChatRequest.compatible(
                    model,
                    normalized.effectiveMaxOutputTokens(),
                    estimatedPrompt,
                    normalized.includeUsage(),
                    normalized.stream(),
                    normalized.normalizedPayload(),
                    normalized.payloadMode(),
                    normalized.droppedFieldCount());
        }
        try {
            return validate(principal, objectMapper.treeToValue(request, ApiChatRequest.class));
        } catch (JsonProcessingException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof ApiChatException controlled) {
                throw controlled;
            }
            throw ApiChatException.invalid(
                    "The request body contains an invalid field type.", null);
        }
    }

    private void validateCompatibleModelAccess(
            ApiKeyPrincipal principal,
            AiModelCacheEntry model) {
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
    }

    private long estimateRawPromptTokens(ObjectNode payload) {
        try {
            // 原始增强负载包含工具 Schema 和结构化输出约束；按完整 UTF-8 字节保守估算可避免漏算新增字段。
            return Math.max(1L,
                    Math.ceilDiv(5L + objectMapper.writeValueAsBytes(payload).length, 3L));
        } catch (JsonProcessingException failure) {
            throw ApiChatException.invalid("Request body cannot be encoded.", null);
        }
    }

    @Override
    public ValidatedApiChatRequest validate(
            ApiKeyPrincipal principal,
            ApiChatRequest request) {
        if (principal == null || request == null) {
            throw reject(
                    ApiChatValidationRejection.of(
                            ApiChatValidationFailureRule.REQUEST_MISSING, null),
                    "Request is required.",
                    null);
        }
        enforceBodySize(request);
        if (request.model() == null || request.model().isBlank() || request.model().length() > 128) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.MODEL_MISSING, "model")
                            .withNameLength(request.model() == null
                                    ? -1 : request.model().length()),
                    "Model is required.",
                    "model");
        }
        AiModelCacheEntry model = findModel(request.model());
        if (!principal.modelIds().contains(model.id())) {
            throw reject(
                    ApiChatValidationRejection.of(
                            ApiChatValidationFailureRule.MODEL_NOT_AUTHORIZED, "model"),
                    ApiChatErrorCode.MODEL_NOT_ALLOWED,
                    "The API Key is not authorized for this model.",
                    "model");
        }
        if (!model.capabilities().contains(AiModelCapabilityCode.CHAT_COMPLETIONS)
                || model.contextWindowTokens() <= 0
                || model.maxOutputTokens() <= 0) {
            throw reject(
                    ApiChatValidationRejection.of(
                            ApiChatValidationFailureRule.MODEL_UNAVAILABLE, "model"),
                    ApiChatErrorCode.MODEL_NOT_FOUND,
                    "The requested model is unavailable.",
                    "model");
        }

        if (request.stream() == null) {
            throw reject(
                    ApiChatValidationRejection.of(
                            ApiChatValidationFailureRule.STREAM_MISSING, "stream"),
                    ApiChatErrorCode.STREAM_REQUIRED,
                    "Only stream=true is supported.",
                    "stream");
        }
        if (!request.stream().isBoolean()) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.STREAM_TYPE_INVALID, "stream")
                            .withActualNode(request.stream()),
                    "stream must be a JSON boolean.",
                    "stream");
        }
        if (!request.stream().booleanValue()) {
            throw reject(
                    ApiChatValidationRejection.of(
                            ApiChatValidationFailureRule.STREAM_FALSE, "stream"),
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
        long toolDefinitionsBytes = validateTools(request.tools());
        validateOptionalParameters(request);
        validateToolParameterRelationships(request);

        if (request.maxCompletionTokens() != null && request.maxTokens() != null) {
            throw reject(
                    ApiChatValidationRejection.of(
                            ApiChatValidationFailureRule.TOKEN_LIMIT_CONFLICT,
                            "max_completion_tokens"),
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
        long estimatedPrompt = estimatePromptTokens(request, toolDefinitionsBytes);
        if (estimatedPrompt > model.contextWindowTokens() - effectiveMax) {
            throw reject(
                    ApiChatValidationRejection.of(
                            ApiChatValidationFailureRule.CONTEXT_LENGTH_EXCEEDED, "messages"),
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
                .orElseThrow(() -> reject(
                        ApiChatValidationRejection.of(
                                ApiChatValidationFailureRule.MODEL_NOT_FOUND, "model"),
                        ApiChatErrorCode.MODEL_NOT_FOUND,
                        "The requested model does not exist or is disabled.",
                        "model"));
    }

    private void enforceBodySize(ApiChatRequest request) {
        try {
            if (objectMapper.writeValueAsBytes(request).length
                    > properties.getRequest().getMaxBodyBytes()) {
                throw reject(
                        ApiChatValidationRejection.of(
                                ApiChatValidationFailureRule.BODY_TOO_LARGE, null),
                        "Request body exceeds 1 MiB.",
                        null);
            }
        } catch (JsonProcessingException exception) {
            throw reject(
                    ApiChatValidationRejection.of(
                            ApiChatValidationFailureRule.BODY_ENCODING_FAILED, null),
                    "Request body cannot be encoded.",
                    null);
        }
    }

    private void validateMessages(List<Message> messages) {
        if (messages == null
                || messages.isEmpty()
                || messages.size() > properties.getRequest().getMaxMessages()) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.MESSAGES_SIZE_INVALID,
                                    "messages")
                            .withCollectionSize(messages == null ? -1 : messages.size()),
                    "messages must contain 1 to 256 items.",
                    "messages");
        }
        Set<String> declaredToolCallIds = new HashSet<>();
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            if (message == null || !ROLES.contains(message.role())) {
                throw reject(
                        ApiChatValidationRejection.of(
                                        ApiChatValidationFailureRule.MESSAGE_ROLE_INVALID,
                                        "messages")
                                .withMessageIndex(index),
                        "Message role is unsupported.",
                        "messages");
            }
            boolean text = validateMessageContent(message, index);
            boolean reasoning = validateReasoningContent(message, index);
            if ("assistant".equals(message.role())) {
                validateAssistantToolCalls(message.toolCalls());
                if (message.toolCalls() != null) {
                    for (ToolCall call : message.toolCalls()) {
                        if (!declaredToolCallIds.add(call.id())) {
                            throw reject(
                                    ApiChatValidationRejection.of(
                                                    ApiChatValidationFailureRule
                                                            .ASSISTANT_TOOL_CALL_ID_DUPLICATE,
                                                    "messages")
                                            .withMessageIndex(index),
                                    "Assistant tool_call IDs must be unique.",
                                    "messages");
                        }
                    }
                }
                if (!text && !reasoning
                        && (message.toolCalls() == null || message.toolCalls().isEmpty())) {
                    throw reject(
                            ApiChatValidationRejection.of(
                                            ApiChatValidationFailureRule
                                                    .ASSISTANT_CONTENT_MISSING,
                                            "messages")
                                    .withMessageIndex(index),
                            "Assistant message requires content, reasoning_content or tool_calls.",
                            "messages");
                }
            } else if (message.toolCalls() != null) {
                throw reject(
                        ApiChatValidationRejection.of(
                                        ApiChatValidationFailureRule.TOOL_CALLS_ROLE_INVALID,
                                        "messages")
                                .withMessageIndex(index),
                        "Only assistant messages may contain tool_calls.", "messages");
            }
            if ("tool".equals(message.role())) {
                if (message.toolCallId() == null || message.toolCallId().isBlank()
                        || message.toolCallId().length() > 128) {
                    throw reject(
                            ApiChatValidationRejection.of(
                                            ApiChatValidationFailureRule
                                                    .TOOL_MESSAGE_CALL_ID_INVALID,
                                            "messages")
                                    .withMessageIndex(index)
                                    .withNameLength(message.toolCallId() == null
                                            ? -1 : message.toolCallId().length()),
                            "Tool message requires tool_call_id.", "messages");
                }
                if (!declaredToolCallIds.contains(message.toolCallId())) {
                    throw reject(
                            ApiChatValidationRejection.of(
                                            ApiChatValidationFailureRule
                                                    .TOOL_MESSAGE_CALL_ID_UNKNOWN,
                                            "messages")
                                    .withMessageIndex(index),
                            "Tool message references an unknown tool_call_id.", "messages");
                }
            } else if (message.toolCallId() != null) {
                throw reject(
                        ApiChatValidationRejection.of(
                                        ApiChatValidationFailureRule.TOOL_CALL_ID_ROLE_INVALID,
                                        "messages")
                                .withMessageIndex(index),
                        "Only tool messages may contain tool_call_id.", "messages");
            }
        }
    }

    private boolean validateMessageContent(Message message, int messageIndex) {
        JsonNode content = message.content();
        String parameter = "messages[" + messageIndex + "].content";
        if (content != null && content.isTextual()) {
            if (content.textValue().length() > MAX_MESSAGE_TEXT_CHARS) {
                throw reject(
                        ApiChatValidationRejection.of(
                                        ApiChatValidationFailureRule.MESSAGE_CONTENT_TOO_LARGE,
                                        parameter)
                                .withMessageIndex(messageIndex),
                        "Message content is too large.",
                        parameter);
            }
            return true;
        }
        if (content == null || content.isNull()) {
            if ("assistant".equals(message.role())) {
                return false;
            }
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.MESSAGE_CONTENT_REQUIRED,
                                    parameter)
                            .withMessageIndex(messageIndex)
                            .withActualNode(content),
                    "Message content must be text.",
                    parameter);
        }
        if (!content.isArray() || "tool".equals(message.role())) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.MESSAGE_CONTENT_TYPE_INVALID,
                                    parameter)
                            .withMessageIndex(messageIndex)
                            .withActualNode(content),
                    "Message content must be text.",
                    parameter);
        }
        if (content.isEmpty()) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.MESSAGE_CONTENT_PARTS_EMPTY,
                                    parameter)
                            .withMessageIndex(messageIndex)
                            .withCollectionSize(0),
                    "Message content parts must not be empty.",
                    parameter);
        }

        int characters = 0;
        for (int partIndex = 0; partIndex < content.size(); partIndex++) {
            JsonNode part = content.get(partIndex);
            String partParameter = parameter + "[" + partIndex + "]";
            if (part == null || !part.isObject()) {
                throw reject(
                        ApiChatValidationRejection.of(
                                        ApiChatValidationFailureRule
                                                .MESSAGE_CONTENT_PART_NOT_OBJECT,
                                        partParameter)
                                .withMessageIndex(messageIndex)
                                .withPartIndex(partIndex)
                                .withActualNode(part),
                        "Message content part must be an object.", partParameter);
            }
            JsonNode type = part.get("type");
            if (type == null || !type.isTextual() || !"text".equals(type.textValue())) {
                throw reject(
                        ApiChatValidationRejection.of(
                                        ApiChatValidationFailureRule
                                                .MESSAGE_CONTENT_PART_TYPE_INVALID,
                                        partParameter + ".type")
                                .withMessageIndex(messageIndex)
                                .withPartIndex(partIndex)
                                .withActualNode(type),
                        "Only text content parts are supported.", partParameter + ".type");
            }
            JsonNode text = part.get("text");
            if (text == null || !text.isTextual()) {
                throw reject(
                        ApiChatValidationRejection.of(
                                        ApiChatValidationFailureRule
                                                .MESSAGE_CONTENT_PART_TEXT_INVALID,
                                        partParameter + ".text")
                                .withMessageIndex(messageIndex)
                                .withPartIndex(partIndex)
                                .withActualNode(text),
                        "Message content part text must be a string.",
                        partParameter + ".text");
            }
            var fields = part.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!"type".equals(field) && !"text".equals(field)) {
                    throw reject(
                            ApiChatValidationRejection.of(
                                            ApiChatValidationFailureRule
                                                    .MESSAGE_CONTENT_PART_FIELD_UNSUPPORTED,
                                            "messages[].content[].field")
                                    .withMessageIndex(messageIndex)
                                    .withPartIndex(partIndex)
                                    .withCollectionSize(part.size()),
                            "Message content part contains an unsupported field.",
                            partParameter + "." + field);
                }
            }
            try {
                characters = Math.addExact(characters, text.textValue().length());
            } catch (ArithmeticException exception) {
                throw reject(
                        ApiChatValidationRejection.of(
                                        ApiChatValidationFailureRule.MESSAGE_CONTENT_TOO_LARGE,
                                        parameter)
                                .withMessageIndex(messageIndex)
                                .withPartIndex(partIndex),
                        "Message content is too large.",
                        parameter);
            }
            if (characters > MAX_MESSAGE_TEXT_CHARS) {
                throw reject(
                        ApiChatValidationRejection.of(
                                        ApiChatValidationFailureRule.MESSAGE_CONTENT_TOO_LARGE,
                                        parameter)
                                .withMessageIndex(messageIndex)
                                .withPartIndex(partIndex),
                        "Message content is too large.",
                        parameter);
            }
        }
        return true;
    }

    private boolean validateReasoningContent(Message message, int index) {
        JsonNode reasoningContent = message.reasoningContent();
        if (reasoningContent == null) {
            return false;
        }
        String parameter = "messages[" + index + "].reasoning_content";
        // 推理历史只能由 assistant 续传；允许其他角色写入会把内部思考混入用户或工具输入，改变模型上下文语义。
        if (!"assistant".equals(message.role()) || !reasoningContent.isTextual()) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.REASONING_CONTENT_INVALID,
                                    parameter)
                            .withMessageIndex(index)
                            .withActualNode(reasoningContent),
                    "reasoning_content is only supported as an assistant string.", parameter);
        }
        if (reasoningContent.textValue().length() > MAX_MESSAGE_TEXT_CHARS) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.REASONING_CONTENT_TOO_LARGE,
                                    parameter)
                            .withMessageIndex(index),
                    "reasoning_content is too large.",
                    parameter);
        }
        return true;
    }

    private void validateAssistantToolCalls(List<ToolCall> toolCalls) {
        if (toolCalls == null) {
            return;
        }
        if (toolCalls.isEmpty() || toolCalls.size() > 128) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule
                                            .ASSISTANT_TOOL_CALLS_SIZE_INVALID,
                                    "messages")
                            .withCollectionSize(toolCalls.size()),
                    "assistant tool_calls size is invalid.",
                    "messages");
        }
        Set<String> ids = new HashSet<>();
        for (int toolIndex = 0; toolIndex < toolCalls.size(); toolIndex++) {
            ToolCall call = toolCalls.get(toolIndex);
            if (call == null || call.id() == null || call.id().isBlank()
                    || call.id().length() > 128 || !ids.add(call.id())
                    || !"function".equals(call.type())
                    || call.function() == null
                    || !validFunctionName(call.function().name())
                    || call.function().arguments() == null
                    || call.function().arguments().length() > 262_144) {
                throw reject(
                        ApiChatValidationRejection.of(
                                        ApiChatValidationFailureRule
                                                .ASSISTANT_TOOL_CALL_INVALID,
                                        "messages")
                                .withToolIndex(toolIndex),
                        "Assistant tool_call is invalid.",
                        "messages");
            }
        }
    }

    private long validateTools(List<Tool> tools) {
        if (tools == null) {
            return 0L;
        }
        if (tools.size() > properties.getRequest().getMaxTools()) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.TOOL_COUNT_EXCEEDED, "tools")
                            .withCollectionSize(tools.size()),
                    "Too many tools were provided.",
                    "tools");
        }
        Set<String> names = new HashSet<>();
        for (int toolIndex = 0; toolIndex < tools.size(); toolIndex++) {
            Tool tool = tools.get(toolIndex);
            if (tool == null) {
                throw invalidTool(ApiChatValidationFailureRule.TOOL_NULL, toolIndex);
            }
            if (!"function".equals(tool.type())) {
                throw invalidTool(
                        ApiChatValidationFailureRule.TOOL_TYPE_NOT_FUNCTION,
                        toolIndex);
            }
            FunctionDefinition function = tool.function();
            if (function == null) {
                throw invalidTool(ApiChatValidationFailureRule.FUNCTION_MISSING, toolIndex);
            }
            if (!validFunctionName(function.name())) {
                throw reject(
                        ApiChatValidationRejection.of(
                                        ApiChatValidationFailureRule.FUNCTION_NAME_INVALID,
                                        "tools")
                                .withToolIndex(toolIndex)
                                .withNameLength(function.name() == null
                                        ? -1 : function.name().length()),
                        "Function tool is invalid.",
                        "tools");
            }
            if (!names.add(function.name())) {
                throw reject(
                        ApiChatValidationRejection.of(
                                        ApiChatValidationFailureRule.FUNCTION_NAME_DUPLICATE,
                                        "tools")
                                .withToolIndex(toolIndex)
                                .withNameLength(function.name().length()),
                        "Function tool is invalid.",
                        "tools");
            }
            if (function.description() != null) {
                // 工具描述限制必须按真实传输的 UTF-8 字节计算，避免中文与 Emoji 被 UTF-16 字符数低估。
                int descriptionBytes = function.description()
                        .getBytes(StandardCharsets.UTF_8).length;
                if (descriptionBytes
                        > properties.getRequest().getMaxToolDescriptionBytes()) {
                    String parameter = "tools[" + toolIndex
                            + "].function.description";
                    throw reject(
                            ApiChatValidationRejection.of(
                                            ApiChatValidationFailureRule
                                                    .FUNCTION_DESCRIPTION_TOO_LARGE,
                                            parameter)
                                    .withToolIndex(toolIndex)
                                    .withDescriptionMetrics(
                                            function.description().length(),
                                            descriptionBytes),
                            "Function tool description exceeds the allowed UTF-8 size.",
                            parameter);
                }
            }
            if (function.parameters() == null) {
                throw invalidTool(
                        ApiChatValidationFailureRule.FUNCTION_PARAMETERS_MISSING,
                        toolIndex);
            }
            if (!function.parameters().isObject()) {
                throw reject(
                        ApiChatValidationRejection.of(
                                        ApiChatValidationFailureRule
                                                .FUNCTION_PARAMETERS_NOT_OBJECT,
                                        "tools")
                                .withToolIndex(toolIndex)
                                .withActualNode(function.parameters()),
                        "Function tool is invalid.",
                        "tools");
            }
            int[] nodes = {0};
            validateSchema(function.parameters(), 1, nodes, toolIndex);
        }
        long toolDefinitionsBytes = encodeToolDefinitions(tools);
        if (toolDefinitionsBytes
                > properties.getRequest().getMaxToolDefinitionsBytes()) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule
                                            .TOOL_DEFINITIONS_TOO_LARGE,
                                    "tools")
                            .withCollectionSize(tools.size())
                            .withToolDefinitionsBytes(toolDefinitionsBytes),
                    "Tool definitions exceed the allowed UTF-8 size.",
                    "tools");
        }
        return toolDefinitionsBytes;
    }

    /**
     * 完整 tools 数组只序列化一次，其字节数同时作为聚合安全预算和 Token 估算输入，避免两处口径漂移。
     */
    private long encodeToolDefinitions(List<Tool> tools) {
        try {
            return objectMapper.writeValueAsBytes(tools).length;
        } catch (JsonProcessingException exception) {
            throw reject(
                    ApiChatValidationRejection.of(
                            ApiChatValidationFailureRule.TOOL_ENCODING_FAILED,
                            "tools"),
                    "Tool cannot be encoded.",
                    "tools");
        }
    }

    private void validateSchema(JsonNode node, int depth, int[] nodes, int toolIndex) {
        nodes[0]++;
        if (depth > MAX_SCHEMA_DEPTH) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.FUNCTION_SCHEMA_TOO_DEEP,
                                    "tools")
                            .withToolIndex(toolIndex)
                            .withSchema(depth, nodes[0]),
                    "Function JSON Schema is too complex.",
                    "tools");
        }
        if (nodes[0] > MAX_SCHEMA_NODES) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.FUNCTION_SCHEMA_TOO_LARGE,
                                    "tools")
                            .withToolIndex(toolIndex)
                            .withSchema(depth, nodes[0]),
                    "Function JSON Schema is too complex.",
                    "tools");
        }
        if (node.isContainerNode()) {
            node.elements().forEachRemaining(child ->
                    validateSchema(child, depth + 1, nodes, toolIndex));
        } else if (!node.isValueNode()) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.FUNCTION_SCHEMA_NODE_INVALID,
                                    "tools")
                            .withToolIndex(toolIndex)
                            .withActualNode(node)
                            .withSchema(depth, nodes[0]),
                    "Function JSON Schema is invalid.",
                    "tools");
        }
    }

    private void validateOptionalParameters(ApiChatRequest request) {
        validateReasoningEffort(request.reasoningEffort());
        validatePromptCacheKey(request.promptCacheKey());
        validateStore(request.store());
        validateServiceTier(request.serviceTier());
        numberInRange(request.temperature(), "temperature", 0, 2);
        numberInRange(request.topP(), "top_p", 0, 1);
        numberInRange(request.presencePenalty(), "presence_penalty", -2, 2);
        numberInRange(request.frequencyPenalty(), "frequency_penalty", -2, 2);
        if (request.seed() != null) {
            integer(request.seed(), "seed");
        }
        if (request.n() != null && integer(request.n(), "n") != 1) {
            throw reject(
                    ApiChatValidationRejection.of(
                            ApiChatValidationFailureRule.N_MUST_EQUAL_ONE, "n"),
                    "n must equal 1.",
                    "n");
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
            throw reject(
                    ApiChatValidationRejection.of(
                            ApiChatValidationFailureRule.TOOL_PARAMETERS_REQUIRED, "tools"),
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
            throw reject(
                    ApiChatValidationRejection.of(
                            ApiChatValidationFailureRule.TOOL_CHOICE_UNDECLARED,
                            "tool_choice"),
                    "tool_choice references an undeclared function.", "tool_choice");
        }
    }

    private void validateStop(JsonNode stop) {
        if (stop == null || stop.isNull()) {
            return;
        }
        if (stop.isTextual()) {
            if (stop.textValue().length() > 1024) {
                throw reject(
                        ApiChatValidationRejection.of(
                                ApiChatValidationFailureRule.STOP_TOO_LONG, "stop"),
                        "stop is too long.",
                        "stop");
            }
            return;
        }
        if (!stop.isArray() || stop.isEmpty() || stop.size() > 4) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.STOP_SHAPE_INVALID,
                                    "stop")
                            .withActualNode(stop)
                            .withCollectionSize(stop.isArray() ? stop.size() : -1),
                    "stop must be a string or up to four strings.",
                    "stop");
        }
        for (JsonNode item : stop) {
            if (!item.isTextual() || item.textValue().length() > 1024) {
                throw reject(
                        ApiChatValidationRejection.of(
                                        ApiChatValidationFailureRule.STOP_ITEM_INVALID,
                                        "stop")
                                .withActualNode(item),
                        "stop contains an invalid item.",
                        "stop");
            }
        }
    }

    private void validateToolChoice(JsonNode choice) {
        if (choice == null) {
            return;
        }
        if (choice.isTextual()) {
            if (!Set.of("none", "auto", "required").contains(choice.textValue())) {
                throw reject(
                        ApiChatValidationRejection.of(
                                        ApiChatValidationFailureRule.TOOL_CHOICE_INVALID,
                                        "tool_choice")
                                .withActualNode(choice),
                        "tool_choice is unsupported.",
                        "tool_choice");
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
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.TOOL_CHOICE_INVALID,
                                    "tool_choice")
                            .withActualNode(choice)
                            .withCollectionSize(choice.isContainerNode()
                                    ? choice.size() : -1),
                    "tool_choice object is invalid.",
                    "tool_choice");
        }
    }

    private long estimatePromptTokens(
            ApiChatRequest request,
            long toolDefinitionsBytes) {
        long bytes = 0;
        long overhead = 0;
        for (Message message : request.messages()) {
            overhead = Math.addExact(overhead, 8);
            if (message.content() != null && message.content().isTextual()) {
                bytes = Math.addExact(bytes,
                        message.content().textValue().getBytes(StandardCharsets.UTF_8).length);
            } else if (message.content() != null && message.content().isArray()) {
                // 已验证的文本 parts 会无分隔符拼接后发往上游；逐项累加 UTF-8 字节与规范化结果完全等价。
                for (JsonNode part : message.content()) {
                    bytes = Math.addExact(bytes,
                            part.get("text").textValue()
                                    .getBytes(StandardCharsets.UTF_8).length);
                }
            }
            if (message.reasoningContent() != null && message.reasoningContent().isTextual()) {
                // 推理历史同样会发往上游，必须计入输入估算，避免以该字段绕过上下文与预扣边界。
                bytes = Math.addExact(bytes, message.reasoningContent().textValue()
                        .getBytes(StandardCharsets.UTF_8).length);
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
            bytes = Math.addExact(bytes, toolDefinitionsBytes);
            overhead = Math.addExact(
                    overhead,
                    Math.multiplyExact((long) request.tools().size(), 12L));
        }
        return Math.addExact(Math.ceilDiv(bytes, 3L), overhead);
    }

    private void validateReasoningEffort(JsonNode effort) {
        if (effort == null) {
            return;
        }
        if (!effort.isTextual() || !REASONING_EFFORTS.contains(effort.textValue())) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.REASONING_EFFORT_INVALID,
                                    "reasoning_effort")
                            .withActualNode(effort),
                    "reasoning_effort is unsupported.", "reasoning_effort");
        }
    }

    private void validatePromptCacheKey(JsonNode cacheKey) {
        if (cacheKey == null) {
            return;
        }
        if (!cacheKey.isTextual() || cacheKey.textValue().isBlank()
                || cacheKey.textValue().getBytes(StandardCharsets.UTF_8).length
                > MAX_PROMPT_CACHE_KEY_BYTES) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.PROMPT_CACHE_KEY_INVALID,
                                    "prompt_cache_key")
                            .withActualNode(cacheKey),
                    "prompt_cache_key must be a nonblank string up to 256 UTF-8 bytes.",
                    "prompt_cache_key");
        }
    }

    private void validateStore(JsonNode store) {
        if (store == null) {
            return;
        }
        if (!store.isBoolean() || store.booleanValue()) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.STORE_INVALID,
                                    "store")
                            .withActualNode(store),
                    "store must be false.",
                    "store");
        }
    }

    private void validateServiceTier(JsonNode tier) {
        if (tier == null) {
            return;
        }
        if (!tier.isTextual() || !SERVICE_TIERS.contains(tier.textValue())) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.SERVICE_TIER_INVALID,
                                    "service_tier")
                            .withActualNode(tier),
                    "service_tier is unsupported.",
                    "service_tier");
        }
    }

    private boolean booleanValue(JsonNode node, String parameter) {
        if (node == null || !node.isBoolean()) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.BOOLEAN_TYPE_INVALID,
                                    parameter)
                            .withActualNode(node),
                    parameter + " must be a JSON boolean.",
                    parameter);
        }
        return node.booleanValue();
    }

    private long positiveInteger(JsonNode node, String parameter) {
        long value = integer(node, parameter);
        if (value <= 0) {
            throw reject(
                    ApiChatValidationRejection.of(
                            ApiChatValidationFailureRule.POSITIVE_INTEGER_REQUIRED,
                            parameter),
                    parameter + " must be positive.",
                    parameter);
        }
        return value;
    }

    private long integer(JsonNode node, String parameter) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.INTEGER_TYPE_INVALID,
                                    parameter)
                            .withActualNode(node),
                    parameter + " must be a JSON integer.",
                    parameter);
        }
        return node.longValue();
    }

    private void numberInRange(
            JsonNode node,
            String parameter,
            double minimum,
            double maximum) {
        if (node == null) {
            return;
        }
        if (!node.isNumber()) {
            throw reject(
                    ApiChatValidationRejection.of(
                                    ApiChatValidationFailureRule.NUMBER_TYPE_INVALID,
                                    parameter)
                            .withActualNode(node),
                    parameter + " must be a JSON number.",
                    parameter);
        }
        double value = node.doubleValue();
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw reject(
                    ApiChatValidationRejection.of(
                            ApiChatValidationFailureRule.NUMBER_OUT_OF_RANGE,
                            parameter),
                    parameter + " is outside its supported range.",
                    parameter);
        }
    }

    private ApiChatException invalidTool(
            ApiChatValidationFailureRule rule,
            int toolIndex) {
        return reject(
                ApiChatValidationRejection.of(rule, "tools")
                        .withToolIndex(toolIndex),
                "Function tool is invalid.",
                "tools");
    }

    private ApiChatException reject(
            ApiChatValidationRejection rejection,
            String message,
            String parameter) {
        logRejection(rejection);
        return ApiChatException.invalid(message, parameter);
    }

    private ApiChatException reject(
            ApiChatValidationRejection rejection,
            ApiChatErrorCode code,
            String message,
            String parameter) {
        logRejection(rejection);
        return new ApiChatException(code, message, parameter);
    }

    /**
     * 校验日志只输出固定规则和有界结构计数；诊断后端异常必须被隔离，不能改变原始拒绝结果。
     */
    private void logRejection(ApiChatValidationRejection rejection) {
        if (!properties.getStreamDiagnostics().isEnabled()) {
            return;
        }
        try {
            LOGGER.warn(
                    "event=api_chat_validation_rejected diagnosticSchema={} traceId={} rule={} parameter={} messageIndex={} toolIndex={} partIndex={} actualNodeType={} collectionSize={} nameLength={} descriptionLength={} descriptionBytes={} toolDefinitionsBytes={} schemaDepth={} schemaNodeCount={}",
                    DIAGNOSTIC_SCHEMA,
                    safeTraceId(MDC.get("apiChatTraceId")),
                    rejection.rule(),
                    ApiChatDiagnosticParameter.sanitize(rejection.parameter()),
                    rejection.messageIndex(),
                    rejection.toolIndex(),
                    rejection.partIndex(),
                    rejection.actualNodeType(),
                    rejection.collectionSize(),
                    rejection.nameLength(),
                    rejection.descriptionLength(),
                    rejection.descriptionBytes(),
                    rejection.toolDefinitionsBytes(),
                    rejection.schemaDepth(),
                    rejection.schemaNodeCount());
        } catch (RuntimeException ignored) {
            // 日志实现异常不得替换原有 ApiChatException，也不得放宽请求校验。
        }
    }

    private static String safeTraceId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}")
                ? value : "absent";
    }

    private static boolean validFunctionName(String name) {
        return name != null && FUNCTION_NAME.matcher(name).matches();
    }
}
