package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * 构造 CLIProxyAPI Images Generation 请求，固定生成单张最终图并请求最多三张完整中间预览。
 *
 * <p>该工厂只生成 JSON，不执行网络请求；尺寸始终按画幅重新计算，因此旧异步快照不能注入任意上游尺寸。</p>
 */
final class OpenAiImagesGenerationRequestFactory {

    private final ObjectMapper objectMapper;

    OpenAiImagesGenerationRequestFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    JsonNode create(AiConversationStreamingRequest request) {
        Objects.requireNonNull(request);
        AiConversationImageGenerationOptions image = Objects.requireNonNull(
                request.modelRequest().imageGeneration(),
                "Image generation options are required");
        String prompt = request.modelRequest().prompt().currentInput().text();
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Image prompt must not be blank");
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", request.modelRequest().modelName());
        root.put("prompt", prompt);
        root.put("n", 1);
        root.put("quality", image.quality().upstreamValue());
        // 旧队列任务可能保存了迁移前尺寸，真正的上游尺寸必须只由服务端画幅白名单决定。
        root.put("size", image.aspect().upstreamSize());
        root.put("output_format", image.outputFormat());
        root.put("output_compression", image.outputCompression());
        root.put("stream", true);
        // 预览协议固定最多三张，不能让旧快照中的兼容字段改变新端点合同。
        root.put("partial_images",
                AiConversationImageGenerationOptions.MAXIMUM_PARTIAL_IMAGES);
        return root;
    }
}
