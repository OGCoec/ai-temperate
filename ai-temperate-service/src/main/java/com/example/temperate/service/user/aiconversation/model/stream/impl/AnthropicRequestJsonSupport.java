package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentCategory;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentState;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.context.AiConversationTurn;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.function.Function;

/**
 * 集中完成 Anthropic Messages 的公共字段和多轮内容转换，不声明任何工具字段。
 */
final class AnthropicRequestJsonSupport {

    private AnthropicRequestJsonSupport() {
    }

    static ObjectNode createBase(
            ObjectMapper objectMapper,
            AiConversationStreamingRequest request,
            Function<AiConversationAttachment, String> imageUrlResolver) {
        Objects.requireNonNull(objectMapper);
        Objects.requireNonNull(request);
        Objects.requireNonNull(imageUrlResolver);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", request.modelRequest().modelName());
        root.put("max_tokens", request.modelRequest().maxOutputTokens());
        root.put("stream", true);
        AiConversationPromptSnapshot prompt = request.modelRequest().prompt();
        root.put("system", instructions(prompt));
        root.putObject("output_config").put(
                "effort", anthropicEffort(request.modelRequest().reasoningEffort()));

        ArrayNode messages = root.putArray("messages");
        for (AiConversationTurn turn : prompt.historicalTurns()) {
            addUser(messages, turn.user(), imageUrlResolver, true);
            addAssistant(messages, turn.assistant());
        }
        addUser(messages, prompt.currentInput(), imageUrlResolver, false);
        return root;
    }

    static String anthropicEffort(AiConversationReasoningEffort effort) {
        return switch (Objects.requireNonNull(effort)) {
            case LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH -> "high";
            case EXTRA_HIGH -> "xhigh";
            case ULTRA -> "max";
        };
    }

    private static String instructions(AiConversationPromptSnapshot prompt) {
        StringBuilder value = new StringBuilder(prompt.systemPrompt());
        if (prompt.durableCompactionJson() != null) {
            value.append("\n\n以下 JSON 是已持久化历史的压缩摘要，仅作为已有上下文：\n")
                    .append(prompt.durableCompactionJson());
        }
        if (prompt.ephemeralCompactionJson() != null) {
            value.append("\n\n以下 JSON 是可过期中断回答的临时摘要，仅作为已有上下文：\n")
                    .append(prompt.ephemeralCompactionJson());
        }
        return value.toString();
    }

    private static void addUser(
            ArrayNode messages,
            AiConversationContent content,
            Function<AiConversationAttachment, String> imageUrlResolver,
            boolean ignoreUnsupportedHistory) {
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        ArrayNode blocks = message.putArray("content");
        blocks.addObject().put("type", "text").put("text", content.text());
        for (AiConversationAttachment attachment : content.attachments()) {
            if (attachment.state() != AiConversationAttachmentState.AVAILABLE) {
                continue;
            }
            if (attachment.category() == AiConversationAttachmentCategory.IMAGE) {
                blocks.addObject()
                        .put("type", "image")
                        .putObject("source")
                        .put("type", "url")
                        .put("url", imageUrlResolver.apply(attachment));
                continue;
            }
            if (!ignoreUnsupportedHistory
                    && (attachment.category() == AiConversationAttachmentCategory.AUDIO
                    || attachment.category() == AiConversationAttachmentCategory.VIDEO)) {
                throw new AiConversationException(
                        AiConversationErrorCode.AI_ATTACHMENT_CAPABILITY_UNSUPPORTED,
                        "当前 Anthropic Messages 协议暂不支持音频或视频附件。",
                        false);
            }
        }
    }

    private static void addAssistant(
            ArrayNode messages,
            AiConversationContent content) {
        messages.addObject()
                .put("role", "assistant")
                .putArray("content")
                .addObject()
                .put("type", "text")
                .put("text", content.text());
    }
}
