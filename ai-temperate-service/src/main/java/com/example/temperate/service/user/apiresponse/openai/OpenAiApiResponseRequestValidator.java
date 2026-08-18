package com.example.temperate.service.user.apiresponse.openai;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 该服务是来校验 OpenAI Responses 常用无状态文本协议并保留原始 JSON，不负责厂商路由、计费或托管工具执行。
 */
public interface OpenAiApiResponseRequestValidator {

    OpenAiApiResponseRequestValidation validate(ObjectNode request);
}
