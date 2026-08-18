package com.example.temperate.service.user.apiresponse;

import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 该服务是来编排公开 Responses 的严格校验、厂商适配、公共推理生命周期以及 JSON/SSE 协议终态。
 */
@FunctionalInterface
public interface ApiResponseService {

    ApiResponseCreation create(
            ApiKeyPrincipal principal,
            ObjectNode request,
            String clientRequestId);

    default ApiResponseCreation create(
            ApiKeyPrincipal principal,
            ApiResponseRequest request) {
        throw new UnsupportedOperationException(
                "Legacy DTO entry point is not implemented by this service.");
    }
}
