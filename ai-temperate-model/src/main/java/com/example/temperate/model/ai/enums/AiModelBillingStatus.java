package com.example.temperate.model.ai.enums;

/**
 * 定义一次模型调用在预扣、结算、退款和人工对账之间的持久化计费状态。
 */
public enum AiModelBillingStatus {

    RESERVED(0),
    SETTLED(1),
    FAILED_REFUNDED(2),
    RECONCILE_REQUIRED(3),
    REFUNDED(4);

    private final int code;

    AiModelBillingStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
