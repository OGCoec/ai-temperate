package com.example.temperate.web.user.aiconversation.api;

import com.example.temperate.common.codec.id.PublicIdCodec;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 表示前端因模型切换或用量阈值请求异步压缩时选择的目标模型。
 */
public record AiConversationCompactionRequest(
        @NotNull
        @Pattern(regexp = PublicIdCodec.ENCODED_PATTERN)
        @Schema(
                description = "用于重新计算上下文百分比的模型公共 ID",
                pattern = PublicIdCodec.ENCODED_PATTERN,
                example = "AAAAAAAAAAE")
        String modelPublicId) {
}
