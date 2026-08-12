package com.example.temperate.service.user.voice.ticket;

import com.example.temperate.service.user.voice.VoiceClientPlatform;
import java.util.regex.Pattern;

/**
 * 表示 Voice Ticket v2 中只由 HMAC 摘要组成的安全状态绑定，用于握手时重新核验当前状态。
 *
 * <p>该记录不保存原始 PreAuth、设备 UUID、Refresh Token 或会话 Cookie；摘要只能在对应内部
 * 服务中解析，不能作为客户端凭据使用。</p>
 */
public record VoiceTicketSecurityBinding(
        long userId,
        VoiceClientPlatform platform,
        String preAuthTokenDigest,
        String preAuthDeviceDigest,
        String sessionReferenceDigest,
        String refreshSessionDigest,
        String sessionDeviceDigest,
        String globalDeviceBlockDigest,
        long webRtcGeneration) {

    private static final Pattern HMAC = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    public VoiceTicketSecurityBinding {
        if (userId <= 0 || platform == null || webRtcGeneration <= 0
                || !digest(preAuthTokenDigest)
                || !digest(preAuthDeviceDigest)
                || !digest(sessionReferenceDigest)
                || !digest(refreshSessionDigest)
                || !digest(sessionDeviceDigest)
                || !digest(globalDeviceBlockDigest)) {
            throw new IllegalArgumentException("Voice ticket security binding is invalid.");
        }
    }

    private static boolean digest(String value) {
        return value != null && HMAC.matcher(value).matches();
    }

    @Override
    public String toString() {
        return "VoiceTicketSecurityBinding[userId=redacted, platform=" + platform
                + ", digests=redacted, webRtcGeneration=" + webRtcGeneration + "]";
    }
}
