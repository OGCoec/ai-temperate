package com.example.temperate.service.user.apichat;

import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 该服务是来在并发获取、额度预扣和 8317 连接前选择宽松或严格请求路径，并统一验证能力、授权和上下文窗口。
 */
public interface ApiChatRequestValidator {

    ValidatedApiChatRequest validate(ApiKeyPrincipal principal, ObjectNode request);

    ValidatedApiChatRequest validate(ApiKeyPrincipal principal, ApiChatRequest request);
}
