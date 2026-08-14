package com.example.temperate.service.user.apichat.upstream;

import com.example.temperate.service.user.apichat.billing.ApiChatBillingService.Usage;

/**
 * 该解析器是来验证并重新序列化 8317 的 OpenAI SSE chunk，识别文本、工具参数、结束原因、最终 Usage 和 `[DONE]`。
 */
public interface ApiChatSseParser {

    ParsedChunk parse(String data);

    /** serializedData 不含 `data:` 前缀，Controller 负责标准 SSE 编码。 */
    record ParsedChunk(
            String serializedData,
            Usage usage,
            boolean done,
            boolean output,
            long outputUtf8Bytes,
            String finishReason,
            boolean usageOnly) {
    }
}
