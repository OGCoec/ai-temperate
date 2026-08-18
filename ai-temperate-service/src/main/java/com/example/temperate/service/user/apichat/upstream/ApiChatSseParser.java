package com.example.temperate.service.user.apichat.upstream;

import com.example.temperate.service.user.aiinference.api.ApiInferenceUsage;
import java.util.List;
import java.util.Objects;

/**
 * 该解析器是来验证 8317 的 OpenAI SSE 最小计费结构并保留原始 data 字段，未知成功字段不得在此边界被裁剪。
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

    /** serializedData 不含 `data:` 前缀，serializedDataWithoutUsage 只供客户端未请求 Usage 时使用。 */
    record ParsedChunk(
            String serializedData,
            String serializedDataWithoutUsage,
            ApiInferenceUsage usage,
            boolean done,
            boolean output,
            long outputUtf8Bytes,
            String finishReason,
            boolean usageOnly) {

        public ParsedChunk(
                String serializedData,
                ApiInferenceUsage usage,
                boolean done,
                boolean output,
                long outputUtf8Bytes,
                String finishReason) {
            this(serializedData, serializedData, usage, done, output,
                    outputUtf8Bytes, finishReason, false);
        }
    }
}
