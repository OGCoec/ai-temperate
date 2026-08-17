package com.example.temperate.service.user.apichat.upstream;

import com.example.temperate.service.user.apichat.billing.ApiChatBillingService.Usage;
import java.util.List;
import java.util.Objects;

/**
 * 该解析器是来验证并重新序列化 8317 的 OpenAI SSE 事件，并把非标准的 choices/Usage 合并终态拆成有序标准帧。
 */
public interface ApiChatSseParser {

    ParsedEvent parse(String data);

    /** 表示解析器是否把一个非标准上游事件拆分成了多个标准事件。 */
    enum Normalization {
        NONE,
        COMBINED_CHOICES_AND_USAGE
    }

    /**
     * 一个上游 SSE data 事件的规范化结果；帧列表不可变且最多包含“业务帧、Usage 帧”两个元素。
     */
    record ParsedEvent(
            List<ParsedChunk> chunks,
            Normalization normalization) {

        public ParsedEvent {
            chunks = List.copyOf(Objects.requireNonNull(chunks));
            normalization = Objects.requireNonNull(normalization);
            if (chunks.isEmpty() || chunks.size() > 2) {
                throw new IllegalArgumentException(
                        "A parsed SSE event must contain one or two chunks");
            }
            if ((normalization == Normalization.NONE && chunks.size() != 1)
                    || (normalization == Normalization.COMBINED_CHOICES_AND_USAGE
                    && chunks.size() != 2)) {
                throw new IllegalArgumentException(
                        "The normalization kind does not match the parsed chunk count");
            }
        }
    }

    /** serializedData 不含 `data:` 前缀，Controller 负责标准 SSE 编码。 */
    record ParsedChunk(
            String serializedData,
            Usage usage,
            boolean done,
            boolean output,
            long outputUtf8Bytes,
            String finishReason) {
    }
}
