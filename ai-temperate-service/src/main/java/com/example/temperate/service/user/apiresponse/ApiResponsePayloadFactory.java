package com.example.temperate.service.user.apiresponse;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 该工厂是来把已验证的 Responses 请求生成规范 8317 负载，并强制覆盖模型、stream、store 与输出上限安全字段。
 */
public interface ApiResponsePayloadFactory {

    ObjectNode create(ValidatedApiResponseRequest request);
}
