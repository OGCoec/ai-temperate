package com.example.temperate.service.admin.login;

import com.example.temperate.service.admin.session.AdminSessionIssue;
import reactor.core.publisher.Mono;

/**
 * 定义管理员登录 Flow 创建以及 hCaptcha 与三项凭证联合校验。
 */
public interface AdminLoginService {

    AdminLoginStartResult start(String deviceInstallationId, String canonicalIp);

    Mono<AdminSessionIssue> complete(AdminLoginCompleteCommand command);
}
