package com.example.temperate.service.user.voice;

import java.util.Objects;

/**
 * 表示语音票据或实时转写在受控边界内失败。
 *
 * <p>异常只携带稳定分类和安全消息，不包含票据、音频、转写正文或上游响应内容。</p>
 */
public final class VoiceException extends RuntimeException {

    private final VoiceErrorCode code;
    private final boolean retryable;

    public VoiceException(VoiceErrorCode code, String message, boolean retryable) {
        this(code, message, retryable, null);
    }

    public VoiceException(
            VoiceErrorCode code,
            String message,
            boolean retryable,
            Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code);
        this.retryable = retryable;
    }

    public VoiceErrorCode code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
