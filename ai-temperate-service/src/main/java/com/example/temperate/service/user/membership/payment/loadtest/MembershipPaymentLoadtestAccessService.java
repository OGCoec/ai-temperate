package com.example.temperate.service.user.membership.payment.loadtest;

import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;

/**
 * 该服务是来为受控会员支付压测路径验证短期 Access Token、用户白名单、账号状态和会员额度存在性。
 */
public interface MembershipPaymentLoadtestAccessService {

    /**
     * 验证不包含 RT、Device 与 CSRF 的压测 AT，并返回只能用于当前请求的最小认证主体。
     */
    SessionPrincipal authenticate(String rawAccessToken);
}
