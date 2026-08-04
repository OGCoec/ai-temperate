package com.example.temperate.web.user.aiconversation.api;

import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 表示图片模型请求中唯一允许由用户选择的画幅，质量与尺寸继续由服务端档位映射。
 */
public record AiConversationImageRequest(
        @NotNull
        @Schema(
                description = "图片画幅：SQUARE 正方形、LANDSCAPE 横图、PORTRAIT 竖图",
                allowableValues = {"SQUARE", "LANDSCAPE", "PORTRAIT"},
                example = "LANDSCAPE")
        AiConversationImageAspect aspect) {
}
