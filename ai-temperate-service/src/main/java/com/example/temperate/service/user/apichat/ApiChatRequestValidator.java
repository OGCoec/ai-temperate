package com.example.temperate.service.user.apichat;

import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;

/**
 * 该服务是来在并发获取、额度预扣和 8317 连接前严格验证 JSON 类型、能力边界、模型授权和上下文窗口。
 */
public interface ApiChatRequestValidator {

    ValidatedApiChatRequest validate(ApiKeyPrincipal principal, ApiChatRequest request);
}
