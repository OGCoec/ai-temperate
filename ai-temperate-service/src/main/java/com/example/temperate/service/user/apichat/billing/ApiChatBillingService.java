package com.example.temperate.service.user.apichat.billing;

import com.example.temperate.service.user.aiinference.api.ApiInferenceBillingService;
import com.example.temperate.service.user.aiinference.api.ApiInferenceExecutionRequest;
import com.example.temperate.service.user.aiinference.api.ApiInferenceProtocol;
import com.example.temperate.service.user.aiinference.api.ApiInferenceReservation;
import com.example.temperate.service.user.apichat.ValidatedApiChatRequest;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;

/**
 * 该兼容接口是来让既有 Chat 调用转入协议中立计费服务，同时保留旧注入点与测试调用方式直至迁移完成。
 */
public interface ApiChatBillingService extends ApiInferenceBillingService {

    default ApiInferenceReservation reserve(
            ApiKeyPrincipal principal,
            ValidatedApiChatRequest request) {
        return reserve(principal, new ApiInferenceExecutionRequest(
                request.model(),
                request.effectiveMaxOutputTokens(),
                request.estimatedPromptTokens(),
                true,
                ApiInferenceProtocol.CHAT_COMPLETIONS));
    }
}
