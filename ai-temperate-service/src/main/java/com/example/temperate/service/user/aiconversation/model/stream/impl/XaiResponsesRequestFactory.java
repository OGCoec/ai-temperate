package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentCategory;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentState;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.context.AiConversationTurn;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.function.Function;

/**
 * 按 xAI Responses 白名单独立构造联网请求，防止 OpenAI 专属工具参数随共享对象泄漏到 xAI。
 */
final class XaiResponsesRequestFactory {

    private final ObjectMapper objectMapper;

    XaiResponsesRequestFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    JsonNode create(AiConversationStreamingRequest request) {
        return create(request, AiConversationAttachment::url);
    }

    JsonNode create(
            AiConversationStreamingRequest request,
            Function<AiConversationAttachment, String> imageUrlResolver) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(imageUrlResolver);
        if (request.modelRequest().provider() != AiModelProvider.XAI) {
            throw new IllegalArgumentException(
                    "xAI Responses request requires the xAI provider.");
        }
        if (request.webSearchMode() == AiConversationWebSearchMode.OFF) {
            throw new IllegalArgumentException(
                    "xAI Responses web search request cannot use OFF mode.");
        }
        AiModelProvider.XAI.validateReasoningEffort(
                request.modelRequest().reasoningEffort());

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", request.modelRequest().modelName());
        root.put("stream", true);
        root.put("store", false);
        root.put("max_output_tokens", request.modelRequest().maxOutputTokens());
        root.putArray("tools").addObject().put("type", "web_search");
        root.put("tool_choice",
                request.webSearchMode() == AiConversationWebSearchMode.REQUIRED
                        ? "required" : "auto");
        root.putArray("include").add("web_search_call.action.sources");
        root.putObject("reasoning").put(
                "effort",
                request.modelRequest().reasoningEffort().upstreamValue());

        AiConversationPromptSnapshot prompt = request.modelRequest().prompt();
        root.put("instructions", instructions(prompt));
        ArrayNode input = root.putArray("input");
        for (AiConversationTurn turn : prompt.historicalTurns()) {
            addUserInput(input, turn.user(), imageUrlResolver, true);
            addAssistantInput(input, turn.assistant());
        }
        addUserInput(input, prompt.currentInput(), imageUrlResolver, false);
        return root;
    }

    private static String instructions(AiConversationPromptSnapshot prompt) {
        StringBuilder instructions = new StringBuilder(prompt.systemPrompt());
        if (prompt.durableCompactionJson() != null) {
            instructions.append("\n\n以下 JSON 是已持久化历史的压缩摘要，仅作为已有上下文：\n")
                    .append(prompt.durableCompactionJson());
        }
        if (prompt.ephemeralCompactionJson() != null) {
            instructions.append("\n\n以下 JSON 是可过期中断回答的临时摘要，仅作为已有上下文：\n")
                    .append(prompt.ephemeralCompactionJson());
        }
        return instructions.toString();
    }

    private static void addUserInput(
            ArrayNode input,
            AiConversationContent content,
            Function<AiConversationAttachment, String> imageUrlResolver,
            boolean ignoreUnsupportedHistory) {
        ObjectNode message = input.addObject();
        message.put("role", "user");
        ArrayNode parts = message.putArray("content");
        parts.addObject().put("type", "input_text").put("text", content.text());
        for (AiConversationAttachment attachment : content.attachments()) {
            if (attachment.state() != AiConversationAttachmentState.AVAILABLE) {
                continue;
            }
            if (attachment.category() == AiConversationAttachmentCategory.IMAGE) {
                parts.addObject()
                        .put("type", "input_image")
                        .put("image_url", imageUrlResolver.apply(attachment));
                continue;
            }
            if (!ignoreUnsupportedHistory
                    && (attachment.category() == AiConversationAttachmentCategory.AUDIO
                    || attachment.category() == AiConversationAttachmentCategory.VIDEO)) {
                throw new AiConversationException(
                        AiConversationErrorCode.AI_ATTACHMENT_CAPABILITY_UNSUPPORTED,
                        "当前 xAI Responses 联网协议暂不支持音频或视频附件",
                        false);
            }
        }
    }

    private static void addAssistantInput(
            ArrayNode input,
            AiConversationContent content) {
        input.addObject()
                .put("role", "assistant")
                .putArray("content")
                .addObject()
                .put("type", "output_text")
                .put("text", content.text());
    }
}
