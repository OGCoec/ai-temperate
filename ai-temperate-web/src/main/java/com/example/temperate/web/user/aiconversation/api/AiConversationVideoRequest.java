package com.example.temperate.web.user.aiconversation.api;

import com.example.temperate.service.user.aiconversation.video.AiConversationVideoAspectRatio;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMode;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoResolution;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 表示用户选择的 xAI 视频模式与控制参数，只接受当前请求附件中的公开标识，不接受外部 URL 或 file_id。
 */
public record AiConversationVideoRequest(
        @NotNull
        @Schema(description = "视频操作模式", example = "IMAGE_TO_VIDEO")
        AiConversationVideoMode mode,
        @Min(0)
        @Max(15)
        @Schema(description = "生成秒数；编辑必须省略，延长表示新增秒数", example = "10")
        Integer durationSeconds,
        @Schema(description = "普通生成清晰度；编辑和延长必须省略", example = "P1080")
        AiConversationVideoResolution resolution,
        @Schema(description = "普通生成画幅；编辑和延长必须省略", example = "RATIO_16_9")
        AiConversationVideoAspectRatio aspectRatio,
        @Size(max = 7)
        List<@Pattern(regexp = "^[A-Za-z0-9_-]{38}$") String>
                inputAttachmentPublicIds) {

    public AiConversationVideoRequest {
        inputAttachmentPublicIds = inputAttachmentPublicIds == null
                ? List.of()
                : List.copyOf(inputAttachmentPublicIds);
    }
}
