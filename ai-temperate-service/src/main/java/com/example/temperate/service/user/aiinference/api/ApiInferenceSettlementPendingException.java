package com.example.temperate.service.user.aiinference.api;

/**
 * 该异常是来区分“模型输出已经完整但结算尚待恢复”与普通上游失败，调用方遇到它时禁止执行系统失败退款。
 */
public final class ApiInferenceSettlementPendingException extends RuntimeException {

    public ApiInferenceSettlementPendingException(Throwable cause) {
        super("API inference settlement remains pending", cause);
    }
}
