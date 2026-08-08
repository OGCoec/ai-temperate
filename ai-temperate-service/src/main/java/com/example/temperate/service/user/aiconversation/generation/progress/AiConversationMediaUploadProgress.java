package com.example.temperate.service.user.aiconversation.generation.progress;

import java.util.Objects;

/**
 * 承载一张生成图片或一个生成视频的 OSS 上传进度，作为临时 SSE 事件数据而不写入生成快照。
 */
public record AiConversationMediaUploadProgress(
        AiConversationMediaType mediaType,
        int outputIndex,
        int attempt,
        int maxAttempts,
        AiConversationMediaUploadState state,
        long transferredBytes,
        Long totalBytes,
        Integer percent,
        long sequence,
        String errorCode) {

    public AiConversationMediaUploadProgress {
        Objects.requireNonNull(mediaType);
        Objects.requireNonNull(state);
        if (outputIndex < 0 || attempt < 1 || maxAttempts < attempt
                || transferredBytes < 0L || sequence < 1L) {
            throw new IllegalArgumentException("Media upload progress is invalid.");
        }
        if (totalBytes != null && (totalBytes < 1L || transferredBytes > totalBytes)) {
            throw new IllegalArgumentException("Media upload byte progress is invalid.");
        }
        if (percent != null && (percent < 0 || percent > 100)) {
            throw new IllegalArgumentException("Media upload percentage is invalid.");
        }
        if (state == AiConversationMediaUploadState.COMPLETED
                && (!Integer.valueOf(100).equals(percent)
                || totalBytes == null
                || transferredBytes != totalBytes)) {
            throw new IllegalArgumentException("Completed upload progress must be verified.");
        }
        if (state == AiConversationMediaUploadState.VERIFYING
                && !Integer.valueOf(99).equals(percent)) {
            throw new IllegalArgumentException("Verifying upload progress must remain at 99 percent.");
        }
        if (state != AiConversationMediaUploadState.FAILED
                && errorCode != null) {
            throw new IllegalArgumentException("Only failed upload progress may contain an error code.");
        }
    }
}
