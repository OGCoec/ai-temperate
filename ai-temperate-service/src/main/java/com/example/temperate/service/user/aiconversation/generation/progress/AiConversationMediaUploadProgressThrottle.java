package com.example.temperate.service.user.aiconversation.generation.progress;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 对单个媒体上传任务的高频字节回调进行节流，保证状态变化立即可见而普通进度不会淹没 SSE 通道。
 */
public final class AiConversationMediaUploadProgressThrottle {

    private static final int MINIMUM_PERCENT_DELTA = 5;
    private static final long MAXIMUM_SILENCE_MILLIS = 200L;

    private final LongSupplier currentTimeMillis;
    private AiConversationMediaUploadState lastState;
    private Integer lastPercent;
    private long lastTransferredBytes;
    private long lastPublishedAtMillis;

    public AiConversationMediaUploadProgressThrottle(LongSupplier currentTimeMillis) {
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis);
    }

    public boolean shouldPublish(AiConversationMediaUploadProgress progress) {
        Objects.requireNonNull(progress);
        long nowMillis = currentTimeMillis.getAsLong();
        boolean first = lastState == null;
        boolean stateChanged = !first && lastState != progress.state();
        boolean percentAdvanced = progress.percent() != null
                && (lastPercent == null
                || progress.percent() - lastPercent >= MINIMUM_PERCENT_DELTA);
        boolean bytesAdvanced = progress.transferredBytes() > lastTransferredBytes;
        boolean silenceExceeded = !first
                && nowMillis - lastPublishedAtMillis >= MAXIMUM_SILENCE_MILLIS;
        boolean publish = first || stateChanged || percentAdvanced
                || (silenceExceeded && bytesAdvanced);
        if (publish) {
            lastState = progress.state();
            lastPercent = progress.percent();
            lastTransferredBytes = progress.transferredBytes();
            lastPublishedAtMillis = nowMillis;
        }
        return publish;
    }
}
