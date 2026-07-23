package com.example.temperate.service.auth.session.authentication.service;

import com.example.temperate.service.auth.session.authentication.dto.command.LogoutCommand;
import com.example.temperate.service.auth.session.authentication.dto.command.SessionAuthenticationCommand;
import com.example.temperate.service.auth.session.authentication.dto.command.SessionBootstrapCommand;
import com.example.temperate.service.auth.session.authentication.dto.result.SessionAuthenticationResult;

/**
 * 定义刷新会话认证、CSRF 启动、登出和按用户撤销会话的业务边界。
 */
public interface SessionAuthenticationService {

    SessionAuthenticationResult authenticate(SessionAuthenticationCommand command);

    SessionAuthenticationResult bootstrap(SessionBootstrapCommand command);

    void logout(LogoutCommand command);

    int revokeAllForUser(long userId);

    /**
     * 撤销当前已认证用户的全部 Refresh Session；重试边界由实现负责，调用方不逐个操作 Redis。
     */
    int logoutAllForUser(long userId);
}
