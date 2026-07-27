package com.example.temperate.service.admin.aimodel.icon.storage;

/**
 * 表示模型图标 OSS 写入或删除失败，并区分固定路径已存在的受控冲突。
 */
public final class AiModelIconStorageException extends RuntimeException {

    private final boolean objectConflict;

    public AiModelIconStorageException(
            String message,
            boolean objectConflict,
            Throwable cause) {
        super(message, cause);
        this.objectConflict = objectConflict;
    }

    public boolean objectConflict() {
        return objectConflict;
    }
}
