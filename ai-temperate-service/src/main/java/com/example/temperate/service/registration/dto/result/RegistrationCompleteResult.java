package com.example.temperate.service.registration.dto.result;

/**
 * 返回注册完成后的公共用户标识和展示资料。
 */
public record RegistrationCompleteResult(
        String publicUserId,
        String registrationTokenToClear) {
}
