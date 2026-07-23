package com.example.temperate.service.auth.login.audit.observer;

import com.example.temperate.service.auth.login.audit.enums.LoginAuditOutcome;
import com.example.temperate.service.auth.login.audit.enums.LoginAuditReason;

/**
 * 定义登录结果的观测出口。
 *
 * <p>实现可将结果写入指标、审计或其他观测系统，但不得改变登录业务结果或保存敏感请求内容。</p>
 */
public interface LoginAuditObserver {

    void observe(LoginAuditOutcome outcome, LoginAuditReason reason);
}
