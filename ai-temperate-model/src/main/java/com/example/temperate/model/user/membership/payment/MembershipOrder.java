package com.example.temperate.model.user.membership.payment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import com.example.temperate.model.auth.enums.MembershipTier;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 该实体是来承载会员支付订单在 PostgreSQL 中的完整事实，包括归属、金额、状态边界和单调版本。
 *
 * <p>二进制 ID 通过防御性复制隔离可变数组；该实体不执行状态迁移、授权判断或第三方支付请求。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class MembershipOrder {

    private byte[] id;
    private Long loginIdentityId;
    private MembershipTier membershipTier;
    private BigDecimal payAmountYuan;
    private String payType;
    private MembershipOrderStatus status;
    private UUID idempotencyKey;
    private String providerTradeNo;
    private OffsetDateTime paymentStartedAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime closingDeadlineAt;
    private OffsetDateTime paidAt;
    private MembershipOrderEntitlementResolution entitlementResolution;
    private OffsetDateTime entitlementResolvedAt;
    private Long stateVersion;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public byte[] getId() {
        return id == null ? null : id.clone();
    }

    public void setId(byte[] id) {
        this.id = id == null ? null : id.clone();
    }
}
