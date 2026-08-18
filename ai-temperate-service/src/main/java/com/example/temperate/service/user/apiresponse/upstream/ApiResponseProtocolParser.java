package com.example.temperate.service.user.apiresponse.upstream;

import com.example.temperate.service.user.aiinference.sse.ApiInferenceSseEvent;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 该解析器是来验证 8317 Responses 的 SSE 与 JSON 权威终态、顺序字段和 Usage，并保留客户端可见原始数据。
 */
public interface ApiResponseProtocolParser {

    ApiResponseSseFrame parseSse(ApiInferenceSseEvent event);

    ApiResponseJsonResult parseJson(JsonNode response);
}
