package com.example.temperate.service.user.apichat.upstream;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 该服务是来校验 Chat 非流式响应的 choices 与 Usage 结算事实，同时原样保留成功响应对象。
 */
public interface ApiChatJsonParser {

    ApiChatJsonResult parse(JsonNode response);
}
