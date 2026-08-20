package com.example.temperate.service.auth.oauth.phone;

import com.example.temperate.service.auth.oauth.flow.ProtectedOAuthFlowAccess;
import java.time.Instant;

/**
 * 定义 OAuth 补手机号发送窗口、冷却冲突、手机号冲突探测和两小时封禁的原子风控边界。
 */
public interface OAuthPhoneRiskService {

    void requireSendAllowed(ProtectedOAuthFlowAccess access, Instant now);

    void recordPhoneConflict(ProtectedOAuthFlowAccess access, Instant now);
}
