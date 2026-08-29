package com.example.temperate.model.user.membership.payment;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 该投影是来承载 PostgreSQL 已提交的退款终态事实，供 Redis 订单快照缺失时进行权威恢复校验。
 *
 * <p>它只描述回调与订单的数据库绑定和终态，不自行触发 Redis 清理、第三方退款或 processing claim 完成。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class MembershipPaymentRefundTerminalFact {

    private byte[] callbackId;
    private byte[] orderId;
    private String providerTradeNo;
    private String callbackResolution;
    private MembershipOrderStatus orderStatus;
    private MembershipOrderEntitlementResolution orderEntitlementResolution;
    private String orderProviderTradeNo;

    public byte[] getCallbackId() {
        return callbackId == null ? null : callbackId.clone();
    }

    public void setCallbackId(byte[] callbackId) {
        this.callbackId = callbackId == null ? null : callbackId.clone();
    }

    public byte[] getOrderId() {
        return orderId == null ? null : orderId.clone();
    }

    public void setOrderId(byte[] orderId) {
        this.orderId = orderId == null ? null : orderId.clone();
    }
}
