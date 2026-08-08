package com.example.temperate.service.user.aiconversation.model.stream.xai.video;

import com.example.temperate.service.user.aiconversation.video.AiConversationVideoGenerationOptions;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * 承载已经解析为短期 OSS HTTPS 地址的 xAI 视频操作上下文，URL 只允许停留在 Worker 内存中。
 */
public record XaiVideoOperationContext(
        String modelName,
        String prompt,
        AiConversationVideoGenerationOptions options,
        List<String> inputUrls) {

    public XaiVideoOperationContext {
        modelName = requireText(modelName, "modelName");
        prompt = requireText(prompt, "prompt");
        options = Objects.requireNonNull(options);
        inputUrls = inputUrls == null ? List.of() : List.copyOf(inputUrls);
        if (inputUrls.size() != options.inputAttachmentPublicIds().size()) {
            throw new IllegalArgumentException(
                    "Resolved video input URL count is inconsistent.");
        }
        inputUrls.forEach(XaiVideoOperationContext::requireHttpsUrl);
    }

    public String requiredSingleInputUrl() {
        if (inputUrls.size() != 1) {
            throw new IllegalArgumentException(
                    "Video operation requires exactly one input URL.");
        }
        return inputUrls.getFirst();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required.");
        }
        return value.trim();
    }

    private static void requireHttpsUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null) {
                throw new IllegalArgumentException(
                        "Video input URL must use HTTPS.");
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Video input URL is invalid.", failure);
        }
    }
}
