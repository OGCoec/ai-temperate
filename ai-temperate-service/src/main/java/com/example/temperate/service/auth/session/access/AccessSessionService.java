package com.example.temperate.service.auth.session.access;

import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;

/**
 * 定义普通 API 使用访问令牌恢复当前会话主体的认证能力。
 */
public interface AccessSessionService {

    SessionPrincipal authenticate(String accessToken);
}
