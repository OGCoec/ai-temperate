package com.example.temperate.model.user.membership.payment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 该实体是来保存已经通过业务校验的会员支付回调审计事实，并与订单形成无物理外键的逻辑关联。
 *
 * <p>它不保存完整原始报文、签名或敏感买家信息，二进制主键和订单 ID 均使用防御性复制。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class MembershipPaymentCallback {

    private byte[] id;
    private byte[] orderId;
    private String providerTradeNo;
    private String tradeStatus;
    private BigDecimal paidAmountYuan;
    private OffsetDateTime paidAt;
    private OffsetDateTime receivedAt;
    private String resolution;
    private OffsetDateTime resolvedAt;

    public byte[] getId() {
        return id == null ? null : id.clone();
    }

    public void setId(byte[] id) {
        this.id = id == null ? null : id.clone();
    }

    public byte[] getOrderId() {
        return orderId == null ? null : orderId.clone();
    }

    public void setOrderId(byte[] orderId) {
        this.orderId = orderId == null ? null : orderId.clone();
    }
}
