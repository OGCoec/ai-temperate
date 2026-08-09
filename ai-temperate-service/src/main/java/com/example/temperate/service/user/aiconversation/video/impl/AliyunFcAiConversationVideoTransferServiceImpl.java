package com.example.temperate.service.user.aiconversation.video.impl;

import com.example.temperate.service.user.aiconversation.attachment.config.AiConversationAttachmentProperties;
import com.example.temperate.service.user.aiconversation.config.AiConversationVideoGenerationProperties;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.generation.progress.AiConversationMediaType;
import com.example.temperate.service.user.aiconversation.generation.progress.AiConversationMediaUploadProgress;
import com.example.temperate.service.user.aiconversation.generation.progress.AiConversationMediaUploadState;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoTransferCommand;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoTransferProgressListener;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoTransferResult;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoTransferService;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * \u8C03\u7528 FC \u89C6\u9891\u642C\u8FD0\u5E76\u6821\u9A8C\u6700\u7EC8 OSS \u5BF9\u8C61\u5143\u6570\u636E.
 */
@Service
public final class AliyunFcAiConversationVideoTransferServiceImpl
        implements AiConversationVideoTransferService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            AliyunFcAiConversationVideoTransferServiceImpl.class);

    private final AliyunFcAiConversationVideoBridgeClient client;
    private final AiConversationVideoGenerationProperties.FunctionCompute properties;
    private final String publicBaseUrl;

    public AliyunFcAiConversationVideoTransferServiceImpl(
            AliyunFcAiConversationVideoBridgeClient client,
            AiConversationVideoGenerationProperties videoProperties,
            AiConversationAttachmentProperties attachmentProperties) {
        this.client = Objects.requireNonNull(client);
        this.properties = Objects.requireNonNull(videoProperties).functionCompute();
        this.publicBaseUrl = Objects.requireNonNull(attachmentProperties).publicBaseUrl();
    }

    @Override
    public AiConversationVideoTransferResult transfer(
            AiConversationVideoTransferCommand command,
            AiConversationVideoTransferProgressListener progressListener) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(progressListener);
        if (!command.targetObjectKey().startsWith(properties.objectPrefix())
                || command.maximumBytes() > properties.maximumVideoBytes()) {
            throw new IllegalArgumentException(
                    "Video transfer target or size exceeds the configured boundary.");
        }
        AtomicLong latestSequence = new AtomicLong();
        AtomicLong latestTransferredBytes = new AtomicLong();
        AtomicReference<Long> latestTotalBytes = new AtomicReference<>();
        AtomicReference<Integer> latestPercent = new AtomicReference<>();
        TransferResponse response;
        try {
            response = client.invokeTransfer(command, TransferResponse.class, progress -> {
                latestSequence.accumulateAndGet(progress.sequence(), Math::max);
                latestTransferredBytes.set(progress.transferredBytes());
                latestTotalBytes.set(progress.totalBytes());
                latestPercent.set(progress.percent());
                notifyProgress(progressListener, progress);
            });
        } catch (RuntimeException failure) {
            String safeErrorCode = failure
                    instanceof AliyunFcVideoTransferFailureException fcFailure
                    ? fcFailure.errorCode()
                    : AiConversationErrorCode.AI_VIDEO_OSS_TRANSFER_FAILED.name();
            // \u4FDD\u7559\u6700\u540E\u4E00\u6B21\u771F\u5B9E\u5B57\u8282\u4F4D\u7F6E\uFF0C\u9632\u6B62\u524D\u7AEF\u4F2A\u9020\u5B8C\u6210.
            notifyProgress(progressListener, failedProgress(
                    latestSequence, latestTransferredBytes, latestTotalBytes,
                    latestPercent, safeErrorCode));
            // 仅记录固定错误码与内部传输标识，禁止把源 URL、OSS Key 或远端原始异常写入日志。
            LOGGER.warn(
                    "event=ai_video_oss_transfer_failed traceId={} stageCode={}",
                    command.transferId(),
                    safeErrorCode);
            throw new AiConversationException(
                    AiConversationErrorCode.AI_VIDEO_OSS_TRANSFER_FAILED,
                    "\u89C6\u9891\u65E0\u6CD5\u5B89\u5168\u4FDD\u5B58\u5230 OSS\u3002",
                    true,
                    failure);
        }
        if (!command.targetObjectKey().equals(response.objectKey())
                || response.byteSize() <= 0L
                || response.byteSize() > command.maximumBytes()
                || !"video/mp4".equalsIgnoreCase(response.contentType())) {
            notifyProgress(progressListener, failedProgress(
                    latestSequence, latestTransferredBytes, latestTotalBytes,
                    latestPercent,
                    AiConversationErrorCode.AI_VIDEO_OSS_TRANSFER_FAILED.name()));
            throw new AiConversationException(
                    AiConversationErrorCode.AI_VIDEO_OSS_TRANSFER_FAILED,
                    "\u89C6\u9891\u642C\u8FD0\u7ED3\u679C\u672A\u901A\u8FC7\u5B89\u5168\u6821\u9A8C\u3002",
                    true,
                    new IllegalStateException(
                            "FC video transfer result failed boundary validation."));
        }
        // \u4EC5\u5728 FC \u5B8C\u6210\u5206\u7247\u5408\u5E76\u4E0E HEAD \u6821\u9A8C\u540E\u624D\u53D1\u5E03 100%.
        notifyProgress(progressListener, new AiConversationMediaUploadProgress(
                AiConversationMediaType.VIDEO,
                0,
                1,
                1,
                AiConversationMediaUploadState.COMPLETED,
                response.byteSize(),
                response.byteSize(),
                100,
                latestSequence.incrementAndGet(),
                null));
        return new AiConversationVideoTransferResult(
                response.objectKey(),
                publicBaseUrl + "/" + response.objectKey(),
                response.byteSize(),
                response.contentType(),
                response.durationMillis(),
                response.width(),
                response.height(),
                response.videoCodec(),
                response.etag(),
                response.checksumSha256());
    }

    private static AiConversationMediaUploadProgress failedProgress(
            AtomicLong latestSequence,
            AtomicLong latestTransferredBytes,
            AtomicReference<Long> latestTotalBytes,
            AtomicReference<Integer> latestPercent,
            String errorCode) {
        return new AiConversationMediaUploadProgress(
                AiConversationMediaType.VIDEO,
                0,
                1,
                1,
                AiConversationMediaUploadState.FAILED,
                latestTransferredBytes.get(),
                latestTotalBytes.get(),
                latestPercent.get(),
                latestSequence.incrementAndGet(),
                errorCode);
    }

    /**
     * \u4E34\u65F6 SSE \u901A\u77E5\u5931\u8D25\u4E0D\u80FD\u53CD\u5411\u5F71\u54CD FC \u54CD\u5E94\u6D88\u8D39.
     */
    private static void notifyProgress(
            AiConversationVideoTransferProgressListener listener,
            AiConversationMediaUploadProgress progress) {
        try {
            listener.onProgress(progress);
        } catch (RuntimeException ignored) {
            // \u8FDB\u5EA6\u4EC5\u7528\u4E8E\u754C\u9762\u53CD\u9988\uFF0C\u4E0D\u80FD\u4E2D\u65AD FC \u54CD\u5E94\u6D88\u8D39.
        }
    }

    /**
     * \u6620\u5C04 FC \u5B8C\u6210 OSS HEAD \u6821\u9A8C\u540E\u8FD4\u56DE\u7684\u53EF\u4FE1\u5BF9\u8C61\u5143\u6570\u636E.
     */
    private record TransferResponse(
            String objectKey,
            long byteSize,
            String contentType,
            long durationMillis,
            int width,
            int height,
            String videoCodec,
            String etag,
            String checksumSha256) {
    }
}
