package com.example.temperate.service.auth.oauth.identity;

import com.example.temperate.service.auth.oauth.domain.OAuthProvider;

/**
 * 表示 OAuth Provider 验证完成后的只读账号解析结果，供短时流程状态机决定下一步。
 *
 * <p>{@code existingIdentityId=0} 表示最终事务需要创建新账号；该内部 ID 只进入受保护服务端流程，
 * 不得直接返回客户端。</p>
 */
public record OAuthAccountDecision(
        OAuthAccountDecisionType type,
        OAuthProvider provider,
        long existingIdentityId,
        boolean phoneRequired) {
}
