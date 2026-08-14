package com.example.temperate.service.user.apikey.management;

/**
 * 该枚举是来稳定区分 API Key 管理的输入、资源、前置条件和并发冲突，供 Web 层映射精确 HTTP 状态。
 */
public enum ApiKeyManagementErrorCode {
    FEATURE_DISABLED,
    INPUT_INVALID,
    PUBLIC_ID_INVALID,
    CURSOR_INVALID,
    MODEL_NOT_FOUND_OR_DISABLED,
    API_KEY_NOT_FOUND,
    VERSION_REQUIRED,
    VERSION_INVALID,
    VERSION_CONFLICT
}
