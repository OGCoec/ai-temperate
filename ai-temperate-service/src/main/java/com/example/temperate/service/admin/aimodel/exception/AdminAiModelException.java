package com.example.temperate.service.admin.aimodel.exception;

import java.util.Objects;

/**
 * 表示管理员 AI 模型新增、查询或启停边界内的受控业务失败。
 *
 * <p>异常消息不携带模型描述、标签、密钥、Redis Value 或数据库 SQL。</p>
 */
public final class AdminAiModelException extends RuntimeException {

    private final AdminAiModelErrorCode code;

    public AdminAiModelException(AdminAiModelErrorCode code, String message) {
        this(code, message, null);
    }

    public AdminAiModelException(
            AdminAiModelErrorCode code,
            String message,
            Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code);
    }

    public AdminAiModelErrorCode code() {
        return code;
    }
}
