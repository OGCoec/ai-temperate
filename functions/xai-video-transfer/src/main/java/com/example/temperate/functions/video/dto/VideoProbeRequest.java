package com.example.temperate.functions.video.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 表示只探测输入 MP4 元数据的源 URL、预期类型和最大字节数。
 */
public final class VideoProbeRequest {

    private final String sourceUrl;
    private final String expectedContentType;
    private final long maximumBytes;

    @JsonCreator
    public VideoProbeRequest(
            @JsonProperty("sourceUrl") String sourceUrl,
            @JsonProperty("expectedContentType") String expectedContentType,
            @JsonProperty("maximumBytes") long maximumBytes) {
        this.sourceUrl = sourceUrl;
        this.expectedContentType = expectedContentType;
        this.maximumBytes = maximumBytes;
    }

    public String sourceUrl() {
        return sourceUrl;
    }

    public String expectedContentType() {
        return expectedContentType;
    }

    public long maximumBytes() {
        return maximumBytes;
    }
}
