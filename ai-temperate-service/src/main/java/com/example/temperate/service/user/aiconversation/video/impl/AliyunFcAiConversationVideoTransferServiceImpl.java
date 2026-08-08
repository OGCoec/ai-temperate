package com.example.temperate.service.user.aiconversation.video.impl;

import com.example.temperate.service.user.aiconversation.attachment.config.AiConversationAttachmentProperties;
import com.example.temperate.service.user.aiconversation.config.AiConversationVideoGenerationProperties;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoTransferCommand;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoTransferResult;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoTransferService;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 同步调用 FC 的 transfer 操作并校验对象引用，业务进程只收发小型 JSON，不接触视频字节。
 */
@Service
public final class AliyunFcAiConversationVideoTransferServiceImpl
        implements AiConversationVideoTransferService {

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
            AiConversationVideoTransferCommand command) {
        Objects.requireNonNull(command);
        if (!command.targetObjectKey().startsWith(properties.objectPrefix())
                || command.maximumBytes() > properties.maximumVideoBytes()) {
            throw new IllegalArgumentException(
                    "Video transfer target or size exceeds the configured boundary.");
        }
		TransferResponse response;
		try {
			response = client.invoke("transfer", command, TransferResponse.class);
		} catch (RuntimeException failure) {
			// xAI 已完成后 FC/OSS 失败仍需按供应商实际成本结算，只向外层暴露稳定交付失败码。
			throw new AiConversationException(
					AiConversationErrorCode.AI_VIDEO_OSS_TRANSFER_FAILED,
					"视频无法安全保存到 OSS。",
					true,
					failure);
		}
        if (!command.targetObjectKey().equals(response.objectKey())
                || response.byteSize() <= 0L
                || response.byteSize() > command.maximumBytes()
                || !"video/mp4".equalsIgnoreCase(response.contentType())) {
            throw new IllegalStateException(
                    "FC video transfer result failed boundary validation.");
        }
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

    /**
     * 映射 FC 在完成 OSS HEAD 校验后返回的对象元数据。
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
