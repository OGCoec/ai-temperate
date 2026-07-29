package com.example.temperate.service.admin.mailinspection.oauth;

import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import reactor.core.publisher.Mono;

/**
 * 使用每行独立 clientId 与 refresh token 异步换取 Outlook IMAP access token。
 */
public interface MicrosoftMailboxOAuthClient {

    /**
     * 执行最多三次的有限 OAuth 交换，并始终返回经过脱敏和稳定分类的结果。
     */
    Mono<MicrosoftMailboxOAuthOutcome> exchange(MailboxCredential credential);
}
