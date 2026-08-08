package com.example.temperate.web.user.aiconversation.api;

import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.util.Objects;

/**
 * 表示用户可选择的图片画幅和输出数量，质量与真实尺寸继续由服务端档位映射。
 */
public final class AiConversationImageRequest {

    @NotNull
    @Schema(
            description = "图片画幅：SQUARE 正方形、LANDSCAPE 横图、PORTRAIT 竖图",
            allowableValues = {"SQUARE", "LANDSCAPE", "PORTRAIT"},
            example = "LANDSCAPE")
    private final AiConversationImageAspect aspect;

    @Min(1)
    @Max(10)
    @Schema(
            description = "本次需要生成或编辑的图片数量；旧客户端省略时按 1 处理",
            minimum = "1",
            maximum = "10",
            defaultValue = "1",
            example = "4")
    private final short outputCount;

    /**
     * 仅在 JSON 边界保留可空包装类型，以便将字段缺省与显式 0 区分开；领域访问器始终返回原生 short。
     */
    @JsonCreator
    public AiConversationImageRequest(
            @JsonProperty("aspect") AiConversationImageAspect aspect,
            @JsonProperty("outputCount")
            @JsonDeserialize(using = StrictNullableShortDeserializer.class)
            Short outputCount) {
        this.aspect = Objects.requireNonNull(aspect);
        this.outputCount = outputCount == null ? (short) 1 : outputCount;
    }

    public AiConversationImageRequest(AiConversationImageAspect aspect) {
        this(aspect, null);
    }

    public AiConversationImageAspect aspect() {
        return aspect;
    }

    public short outputCount() {
        return outputCount;
    }

    /**
     * 仅接受 JSON 整数 Token，防止 Jackson 把小数或字符串静默强制转换为图片数量。
     */
    public static final class StrictNullableShortDeserializer
            extends JsonDeserializer<Short> {

        @Override
        public Short deserialize(
                JsonParser parser,
                DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return (Short) context.handleUnexpectedToken(
                        Short.class, parser);
            }
            int value = parser.getIntValue();
            if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
                return (Short) context.handleWeirdNumberValue(
                        Short.class,
                        value,
                        "Image output count exceeds the short range");
            }
            return (short) value;
        }
    }
}
