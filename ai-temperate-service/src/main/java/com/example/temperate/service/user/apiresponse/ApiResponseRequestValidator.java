package com.example.temperate.service.user.apiresponse;

import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 该服务是来在并发准入与预扣之前严格验证 Codex 核心 Responses 子集，并产生唯一可计费的规范请求视图。
 */
public interface ApiResponseRequestValidator {

    ValidatedApiResponseRequest validate(
            ApiKeyPrincipal principal,
            ObjectNode request);

    ValidatedApiResponseRequest validate(
            ApiKeyPrincipal principal,
            ApiResponseRequest request);
}
