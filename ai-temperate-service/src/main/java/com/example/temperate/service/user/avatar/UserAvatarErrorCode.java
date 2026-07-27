package com.example.temperate.service.user.avatar;

/**
 * 定义用户头像业务可稳定映射到 HTTP 响应的错误分类。
 */
public enum UserAvatarErrorCode {
    INVALID_INPUT,
    TEMP_OBJECT_NOT_FOUND,
    INVALID_IMAGE,
    STORAGE_UNAVAILABLE,
    PROFILE_UNAVAILABLE,
    PERSISTENCE_FAILED
}
