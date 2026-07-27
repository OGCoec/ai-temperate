package com.example.temperate.service.risk.webrtc.validation;

/**
 * 表示客户端 WebRTC 报告超过边界或包含非公网、非字面量地址。
 */
public final class WebRtcInvalidReportException extends IllegalArgumentException {

    public WebRtcInvalidReportException() {
        super("WebRTC IP report is invalid.");
    }
}
