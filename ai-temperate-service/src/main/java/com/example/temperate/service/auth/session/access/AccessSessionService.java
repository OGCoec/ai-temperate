package com.example.temperate.service.auth.session.access;

import com.example.temperate.service.auth.session.access.dto.SessionAccessCommand;
import com.example.temperate.service.auth.session.access.dto.SessionAccessResult;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;

/**
 * 定义普通 API 先校验 Refresh Session、再验证或续签 Access Token 的统一认证能力。
 */
public interface AccessSessionService {

    SessionAccessResult authenticateOrRenew(SessionAccessCommand command);

    SessionAccessResult authenticateOrRenew(
            SessionAccessCommand command,
            PreAuthSessionBinding preAuthBinding);
}
