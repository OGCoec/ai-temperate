package com.example.temperate.functions.video.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 返回已完成 OSS HEAD 校验的视频对象与可信媒体元数据，禁止返回视频正文。
 */
public final class VideoTransferResponse {

    private final String objectKey;
    private final long byteSize;
    private final String contentType;
    private final long durationMillis;
    private final int width;
    private final int height;
    private final String videoCodec;
    private final String etag;
    private final String checksumSha256;

    public VideoTransferResponse(
            String objectKey,
            long byteSize,
            String contentType,
            long durationMillis,
            int width,
            int height,
            String videoCodec,
            String etag,
            String checksumSha256) {
        this.objectKey = objectKey;
        this.byteSize = byteSize;
        this.contentType = contentType;
        this.durationMillis = durationMillis;
        this.width = width;
        this.height = height;
        this.videoCodec = videoCodec;
        this.etag = etag;
        this.checksumSha256 = checksumSha256;
    }

    @JsonProperty("objectKey")
    public String objectKey() {
        return objectKey;
    }

    @JsonProperty("byteSize")
    public long byteSize() {
        return byteSize;
    }

    @JsonProperty("contentType")
    public String contentType() {
        return contentType;
    }

    @JsonProperty("durationMillis")
    public long durationMillis() {
        return durationMillis;
    }

    @JsonProperty("width")
    public int width() {
        return width;
    }

    @JsonProperty("height")
    public int height() {
        return height;
    }

    @JsonProperty("videoCodec")
    public String videoCodec() {
        return videoCodec;
    }

    @JsonProperty("etag")
    public String etag() {
        return etag;
    }

    @JsonProperty("checksumSha256")
    public String checksumSha256() {
        return checksumSha256;
    }
}
