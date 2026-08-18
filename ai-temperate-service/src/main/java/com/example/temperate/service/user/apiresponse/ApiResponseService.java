package com.example.temperate.service.user.apiresponse;

import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;

/**
 * 该服务是来编排公开 Responses 的严格校验、厂商适配、公共推理生命周期以及 JSON/SSE 协议终态。
 */
public interface ApiResponseService {

    ApiResponseCreation create(
            ApiKeyPrincipal principal,
            ApiResponseRequest request);
}
