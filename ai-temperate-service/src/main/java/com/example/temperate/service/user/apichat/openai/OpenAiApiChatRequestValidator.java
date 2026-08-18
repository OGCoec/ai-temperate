package com.example.temperate.service.user.apichat.openai;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 该服务是来校验 OpenAI Chat Completions 常用文本协议并保留已批准的原始 JSON 字段，不负责模型授权和额度预扣。
 */
public interface OpenAiApiChatRequestValidator {

    OpenAiApiChatRequestValidation validate(ObjectNode request);
}
