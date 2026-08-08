package com.example.temperate.functions.video.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 表示视频搬运所需的不可预测幂等标识、源 URL、服务端目标 Key 和硬大小边界。
 */
public final class VideoTransferRequest {

    private final String transferId;
    private final String sourceUrl;
    private final String targetObjectKey;
    private final String expectedContentType;
    private final long maximumBytes;

    @JsonCreator
    public VideoTransferRequest(
            @JsonProperty("transferId") String transferId,
            @JsonProperty("sourceUrl") String sourceUrl,
            @JsonProperty("targetObjectKey") String targetObjectKey,
            @JsonProperty("expectedContentType") String expectedContentType,
            @JsonProperty("maximumBytes") long maximumBytes) {
        this.transferId = transferId;
        this.sourceUrl = sourceUrl;
        this.targetObjectKey = targetObjectKey;
        this.expectedContentType = expectedContentType;
        this.maximumBytes = maximumBytes;
    }

    public String transferId() {
        return transferId;
    }

    public String sourceUrl() {
        return sourceUrl;
    }

    public String targetObjectKey() {
        return targetObjectKey;
    }

    public String expectedContentType() {
        return expectedContentType;
    }

    public long maximumBytes() {
        return maximumBytes;
    }
}
