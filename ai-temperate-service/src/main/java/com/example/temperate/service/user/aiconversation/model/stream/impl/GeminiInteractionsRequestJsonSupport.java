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
 * 构造 Google Interactions 文本协议共有字段，并以 user_input/model_output Step 保留多轮上下文。
 */
final class GeminiInteractionsRequestJsonSupport {

    private GeminiInteractionsRequestJsonSupport() {
    }

    static ObjectNode createTextBase(
            ObjectMapper objectMapper,
            AiConversationStreamingRequest request,
            Function<AiConversationAttachment, String> imageUrlResolver) {
        Objects.requireNonNull(objectMapper);
        Objects.requireNonNull(request);
        Objects.requireNonNull(imageUrlResolver);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", request.modelRequest().modelName());
        root.put("stream", true);
        root.put("store", false);
        AiConversationPromptSnapshot prompt = request.modelRequest().prompt();
        root.put("system_instruction", instructions(prompt));
        root.putObject("generation_config")
                .put("max_output_tokens", request.modelRequest().maxOutputTokens())
                .put("thinking_level", thinkingLevel(
                        request.modelRequest().reasoningEffort()))
                .put("thinking_summaries", "auto");

        ArrayNode input = root.putArray("input");
        for (AiConversationTurn turn : prompt.historicalTurns()) {
            addUserStep(input, turn.user(), imageUrlResolver, true);
            addModelStep(input, turn.assistant());
        }
        addUserStep(input, prompt.currentInput(), imageUrlResolver, false);
        return root;
    }

    static String thinkingLevel(AiConversationReasoningEffort effort) {
        return switch (Objects.requireNonNull(effort)) {
            case LOW -> "minimal";
            case MEDIUM -> "low";
            case HIGH -> "medium";
            case EXTRA_HIGH -> "high";
            case ULTRA -> throw new AiConversationException(
                    AiConversationErrorCode.AI_REQUEST_INVALID,
                    "Google 文本模型不支持第五档推理强度。",
                    false);
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

    private static void addUserStep(
            ArrayNode input,
            AiConversationContent content,
            Function<AiConversationAttachment, String> imageUrlResolver,
            boolean ignoreUnsupportedHistory) {
        ObjectNode step = input.addObject();
        step.put("type", "user_input");
        ArrayNode blocks = step.putArray("content");
        blocks.addObject().put("type", "text").put("text", content.text());
        for (AiConversationAttachment attachment : content.attachments()) {
            if (attachment.state() != AiConversationAttachmentState.AVAILABLE) {
                continue;
            }
            if (attachment.category() == AiConversationAttachmentCategory.IMAGE) {
                blocks.addObject()
                        .put("type", "image")
                        .put("uri", imageUrlResolver.apply(attachment));
                continue;
            }
            if (!ignoreUnsupportedHistory
                    && (attachment.category() == AiConversationAttachmentCategory.AUDIO
                    || attachment.category() == AiConversationAttachmentCategory.VIDEO)) {
                throw new AiConversationException(
                        AiConversationErrorCode.AI_ATTACHMENT_CAPABILITY_UNSUPPORTED,
                        "当前 Google Interactions 协议暂不支持音频或视频附件。",
                        false);
            }
        }
    }

    private static void addModelStep(
            ArrayNode input,
            AiConversationContent content) {
        input.addObject()
                .put("type", "model_output")
                .putArray("content")
                .addObject()
                .put("type", "text")
                .put("text", content.text());
    }
}
