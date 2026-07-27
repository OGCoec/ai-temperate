package com.example.temperate.service.admin.session;

import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;

/**
 * 定义唯一管理员不透明会话的签发、滑动续期和两种退出操作。
 */
public interface AdminSessionService {

    AdminSessionIssue issue(String deviceInstallationId);

    AdminSessionProfile touch(String rawToken, String deviceInstallationId);

    AdminSessionProfile touch(
            String rawToken,
            String deviceInstallationId,
            PreAuthSessionBinding preAuthBinding);

    void logout(String rawToken);

    void logoutAll();
}
