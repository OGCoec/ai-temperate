package com.example.temperate.service.user.aimodel.exception;

import java.util.Objects;

/**
 * 表示普通用户读取模型目录时发生的可映射业务失败。
 */
public final class UserAiModelCatalogException extends RuntimeException {

    private final UserAiModelCatalogErrorCode code;

    public UserAiModelCatalogException(
            UserAiModelCatalogErrorCode code,
            String message) {
        super(message);
        this.code = Objects.requireNonNull(code);
    }

    public UserAiModelCatalogException(
            UserAiModelCatalogErrorCode code,
            String message,
            Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code);
    }

    public UserAiModelCatalogErrorCode code() {
        return code;
    }
}
