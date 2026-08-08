package com.example.temperate.service.user.voice;

/**
 * 表示一次语音转写票据绑定的客户端平台。
 *
 * <p>该枚举只记录已经通过 HTTP 会话认证的平台，不接受 WebSocket 客户端自行覆盖。</p>
 */
public enum VoiceClientPlatform {
    H5,
    ANDROID
}
