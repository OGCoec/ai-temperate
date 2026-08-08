package com.example.temperate.service.user.aiconversation.video;

import java.util.List;
import java.util.Objects;

/**
 * 冻结已经过模型能力、附件归属和可信媒体探测校验的视频参数，供异步 Worker 重放同一请求。
 *
 * <p>编辑和延长虽然不会把清晰度发送给 xAI，但仍保存继承后的 480p/720p 档位用于确定性预扣。</p>
 */
public record AiConversationVideoGenerationOptions(
        AiConversationVideoMode mode,
        int durationSeconds,
        AiConversationVideoResolution resolution,
        AiConversationVideoAspectRatio aspectRatio,
        List<String> inputAttachmentPublicIds,
        long inputVideoDurationMillis,
        int inputVideoWidth,
        int inputVideoHeight,
        String inputVideoCodec) {

    public AiConversationVideoGenerationOptions {
        mode = Objects.requireNonNull(mode);
        inputAttachmentPublicIds = inputAttachmentPublicIds == null
                ? List.of()
                : List.copyOf(inputAttachmentPublicIds);
        switch (mode) {
            case TEXT_TO_VIDEO -> validateGenerated(
                    durationSeconds, resolution, aspectRatio,
                    inputAttachmentPublicIds, 0, 0);
            case IMAGE_TO_VIDEO -> validateGenerated(
                    durationSeconds, resolution, aspectRatio,
                    inputAttachmentPublicIds, 1, 1);
            case REFERENCE_TO_VIDEO -> {
                validateGenerated(
                        durationSeconds, resolution, aspectRatio,
                        inputAttachmentPublicIds, 1, 7);
                if (resolution == AiConversationVideoResolution.P1080) {
                    throw new IllegalArgumentException(
                            "Reference video generation supports at most 720p.");
                }
            }
            case VIDEO_EDIT -> {
                if (durationSeconds != 0 || aspectRatio != null) {
                    throw new IllegalArgumentException(
                            "Video editing cannot override duration or aspect ratio.");
                }
                validateVideoInput(
                        inputAttachmentPublicIds,
                        inputVideoDurationMillis,
                        inputVideoWidth,
                        inputVideoHeight,
                        inputVideoCodec,
                        1L,
                        8_700L);
                Objects.requireNonNull(resolution);
            }
            case VIDEO_EXTEND -> {
                if (durationSeconds < 2 || durationSeconds > 10
                        || aspectRatio != null) {
                    throw new IllegalArgumentException(
                            "Video extension duration or aspect ratio is invalid.");
                }
                validateVideoInput(
                        inputAttachmentPublicIds,
                        inputVideoDurationMillis,
                        inputVideoWidth,
                        inputVideoHeight,
                        inputVideoCodec,
                        2_000L,
                        15_000L);
                Objects.requireNonNull(resolution);
            }
        }
    }

    public int inputImageCount() {
        return switch (mode) {
            case IMAGE_TO_VIDEO, REFERENCE_TO_VIDEO ->
                    inputAttachmentPublicIds.size();
            default -> 0;
        };
    }

    private static void validateGenerated(
            int durationSeconds,
            AiConversationVideoResolution resolution,
            AiConversationVideoAspectRatio aspectRatio,
            List<String> attachmentIds,
            int minimumAttachments,
            int maximumAttachments) {
        if (durationSeconds < 1 || durationSeconds > 15) {
            throw new IllegalArgumentException(
                    "Video generation duration must be between 1 and 15 seconds.");
        }
        Objects.requireNonNull(resolution);
        Objects.requireNonNull(aspectRatio);
        if (attachmentIds.size() < minimumAttachments
                || attachmentIds.size() > maximumAttachments) {
            throw new IllegalArgumentException(
                    "Video generation attachment count is invalid.");
        }
    }

    private static void validateVideoInput(
            List<String> attachmentIds,
            long durationMillis,
            int width,
            int height,
            String codec,
            long minimumDurationMillis,
            long maximumDurationMillis) {
        if (attachmentIds.size() != 1
                || durationMillis < minimumDurationMillis
                || durationMillis > maximumDurationMillis) {
            throw new IllegalArgumentException(
                    "Video input count or duration is invalid.");
        }
        new AiConversationVideoInputMetadata(
                durationMillis, width, height, codec);
    }
}
