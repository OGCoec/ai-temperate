package com.example.temperate.service.registration.dto.query;
import com.example.temperate.service.registration.flow.security.RegistrationAccess;

/**
 * 表示查询注册流程当前状态所需的受控访问凭据。
 */
public record RegistrationStatusQuery(RegistrationAccess access) {
}
