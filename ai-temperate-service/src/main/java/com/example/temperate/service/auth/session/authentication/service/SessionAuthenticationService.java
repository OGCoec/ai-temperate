package com.example.temperate.service.auth.session.authentication.service;

import com.example.temperate.service.auth.session.authentication.dto.command.LogoutCommand;
import com.example.temperate.service.auth.session.authentication.dto.command.SessionBootstrapCommand;
import com.example.temperate.service.auth.session.authentication.dto.result.SessionAuthenticationResult;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;

/**
 * 定义 H5 会话恢复、当前设备登出和按用户撤销全部 Refresh Session 的业务边界。
 */
public interface SessionAuthenticationService {

    SessionAuthenticationResult bootstrap(SessionBootstrapCommand command);

    SessionAuthenticationResult bootstrap(
            SessionBootstrapCommand command,
            PreAuthSessionBinding preAuthBinding);

    void logout(LogoutCommand command);

    int revokeAllForUser(long userId);

    /**
     * 撤销当前已认证用户的全部 Refresh Session；重试边界由实现负责，调用方不逐个操作 Redis。
     */
    int logoutAllForUser(long userId);
}
