package com.example.temperate.model.user.membership.payment;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 该投影是来逐项返回支付回调批量写入的解析结果，使上层区分新增、合法重复和跨订单冲突。
 *
 * <p>结果顺序由输入序号稳定确定；该类型不自行决定是否推进订单状态。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class MembershipPaymentCallbackWriteResult {

    private Integer ordinal;
    private byte[] callbackId;
    private byte[] requestedOrderId;
    private byte[] persistedOrderId;
    private byte[] persistedCallbackId;
    private String providerTradeNo;
    private String tradeStatus;
    private String resolution;
    private String outcome;
    private Boolean inserted;
    private Boolean duplicate;
    private Boolean sameCallback;
    private Boolean orderMismatch;

    public byte[] getCallbackId() {
        return callbackId == null ? null : callbackId.clone();
    }

    public void setCallbackId(byte[] callbackId) {
        this.callbackId = callbackId == null ? null : callbackId.clone();
    }

    public byte[] getRequestedOrderId() {
        return requestedOrderId == null ? null : requestedOrderId.clone();
    }

    public void setRequestedOrderId(byte[] requestedOrderId) {
        this.requestedOrderId = requestedOrderId == null ? null : requestedOrderId.clone();
    }

    public byte[] getPersistedOrderId() {
        return persistedOrderId == null ? null : persistedOrderId.clone();
    }

    public void setPersistedOrderId(byte[] persistedOrderId) {
        this.persistedOrderId = persistedOrderId == null ? null : persistedOrderId.clone();
    }

    public byte[] getPersistedCallbackId() {
        return persistedCallbackId == null ? null : persistedCallbackId.clone();
    }

    public void setPersistedCallbackId(byte[] persistedCallbackId) {
        this.persistedCallbackId = persistedCallbackId == null
                ? null
                : persistedCallbackId.clone();
    }
}
