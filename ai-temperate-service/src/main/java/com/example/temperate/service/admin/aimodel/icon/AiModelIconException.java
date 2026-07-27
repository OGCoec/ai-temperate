package com.example.temperate.service.admin.aimodel.icon;

import java.util.Objects;

/**
 * 表示管理员模型图标业务边界内可预期且可安全返回的失败。
 *
 * <p>异常消息不得包含完整外部 URL、OSS Object Key、图片字节或数据库语句。</p>
 */
public final class AiModelIconException extends RuntimeException {

    private final AiModelIconErrorCode code;

    public AiModelIconException(AiModelIconErrorCode code, String message) {
        this(code, message, null);
    }

    public AiModelIconException(
            AiModelIconErrorCode code,
            String message,
            Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code);
    }

    public AiModelIconErrorCode code() {
        return code;
    }
}
