package com.example.temperate.service.user.aiconversation.model;

/**
 * 区分模型调用使用 Token 还是供应商精确成本 ticks 作为权威计量证据。
 */
public enum AiConversationMeteringBasis {
    TOKEN(0),
    PROVIDER_COST_TICKS(1);

    private final int code;

    AiConversationMeteringBasis(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static AiConversationMeteringBasis fromCode(Integer code) {
        if (code != null) {
            for (AiConversationMeteringBasis value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
        }
        throw new IllegalArgumentException("Unknown AI conversation metering basis.");
    }
}
