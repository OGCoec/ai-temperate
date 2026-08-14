package com.example.temperate.service.user.apichat.provider;

import com.example.temperate.service.user.apichat.ValidatedApiChatRequest;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 该工厂是来把已验证请求重新编码为 8317 接受的 OpenAI 核心 JSON，并强制统一有效输出上限和最终 Usage。
 */
public interface ApiChatPayloadFactory {

    ObjectNode create(ValidatedApiChatRequest request);
}
