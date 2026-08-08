package com.example.temperate.functions.video.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 返回从 MP4 moov 元数据中解析出的时长、尺寸和视频编码。
 */
public final class VideoProbeResponse {

    private final long durationMillis;
    private final int width;
    private final int height;
    private final String videoCodec;

    public VideoProbeResponse(
            long durationMillis,
            int width,
            int height,
            String videoCodec) {
        this.durationMillis = durationMillis;
        this.width = width;
        this.height = height;
        this.videoCodec = videoCodec;
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
}
