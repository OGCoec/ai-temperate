package com.example.temperate.service.auth.identity.bloom;

/**
 * 表示邮箱和手机号计数器原子更新的受控结果。
 */
public enum IdentityPresenceMutationResult {
    APPLIED,
    ALREADY_APPLIED,
    OVERFLOW,
    UNDERFLOW,
    CAPACITY_EXCEEDED,
    UNAVAILABLE
}
