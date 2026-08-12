package com.example.temperate.service.auth.session.access;

import com.example.temperate.service.auth.session.access.dto.SessionAccessCommand;
import com.example.temperate.service.auth.session.access.dto.SessionAccessResult;
import com.example.temperate.service.auth.session.access.dto.SessionBindingAccessCommand;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;

/**
 * 定义普通 API 的 RT-first 认证，以及 Voice 握手对既有 Refresh Session 的只读绑定复核能力。
 *
 * <p>只读绑定复核不续期、不轮换 CSRF、也不签发 Access Token。</p>
 */
public interface AccessSessionService {

    SessionAccessResult authenticateOrRenew(SessionAccessCommand command);

    SessionAccessResult authenticateOrRenew(
            SessionAccessCommand command,
            PreAuthSessionBinding preAuthBinding);

    SessionPrincipal validateActiveBinding(SessionBindingAccessCommand command);
}
