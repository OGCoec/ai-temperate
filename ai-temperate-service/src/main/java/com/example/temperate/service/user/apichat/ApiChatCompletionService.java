package com.example.temperate.service.user.apichat;

import com.example.temperate.service.user.apichat.diagnostic.ApiChatDiagnosticStage;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatStreamDiagnostic;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.fasterxml.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Flux;

/**
 * 该服务是来编排公开 Chat Completions 的验证、并发、预扣、8317 流、Usage 结算、取消补偿与租约释放。
 */
@FunctionalInterface
public interface ApiChatCompletionService {

    ApiChatCompletionCreation create(
            ApiKeyPrincipal principal,
            ObjectNode request,
            String clientRequestId);

    @ApiChatStreamDiagnostic(ApiChatDiagnosticStage.COMPLETION_SERVICE)
    default Flux<String> stream(ApiKeyPrincipal principal, ApiChatRequest request) {
        return Flux.error(new UnsupportedOperationException(
                "Legacy Chat DTO entry point is not implemented by this service."));
    }
}
