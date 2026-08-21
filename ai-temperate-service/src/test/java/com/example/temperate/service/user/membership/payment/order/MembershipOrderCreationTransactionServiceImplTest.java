package com.example.temperate.service.user.membership.payment.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.order.impl.MembershipOrderCreationTransactionServiceImpl;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 该单元测试是来约束数据库幂等胜出订单的归属和创建意图校验，事务服务不得调用 Redis 或 RabbitMQ。
 */
final class MembershipOrderCreationTransactionServiceImplTest {

    @Test
    void insertConflictReadsWinnerAndReturnsIdempotentReplay() {
        MembershipOrderMapper mapper = mock(MembershipOrderMapper.class);
        MembershipOrder candidate = order(17L, MembershipTier.PLUS, "alipay");
        when(mapper.findByIdempotencyKey(candidate.getIdempotencyKey()))
                .thenReturn(null, candidate);
        when(mapper.insert(candidate)).thenReturn(0);

        MembershipOrderCreationResult result =
                new MembershipOrderCreationTransactionServiceImpl(mapper)
                        .createOrGet(candidate);

        assertThat(result.created()).isFalse();
        assertThat(result.order()).isSameAs(candidate);
    }

    @Test
    void sameUuidForDifferentUserOrTierIsRejected() {
        MembershipOrderMapper mapper = mock(MembershipOrderMapper.class);
        MembershipOrder candidate = order(17L, MembershipTier.PLUS, "alipay");
        MembershipOrder winner = order(18L, MembershipTier.PRO, "alipay");
        when(mapper.findByIdempotencyKey(candidate.getIdempotencyKey())).thenReturn(winner);

        assertThatThrownBy(() -> new MembershipOrderCreationTransactionServiceImpl(mapper)
                .createOrGet(candidate))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_ORDER_IDEMPOTENCY_CONFLICT));
    }

    @Test
    void missingLogicalLoginIdentityReturnsControlledStateConflict() {
        MembershipOrderMapper mapper = mock(MembershipOrderMapper.class);
        MembershipOrder candidate = order(17L, MembershipTier.PLUS, "alipay");
        when(mapper.insert(candidate)).thenReturn(0);

        assertThatThrownBy(() -> new MembershipOrderCreationTransactionServiceImpl(mapper)
                        .createOrGet(candidate))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT));
    }

    private static MembershipOrder order(
            long userId,
            MembershipTier tier,
            String payType) {
        MembershipOrder order = new MembershipOrder();
        byte[] id = new byte[16];
        Arrays.fill(id, (byte) userId);
        order.setId(id);
        order.setLoginIdentityId(userId);
        order.setMembershipTier(tier);
        order.setPayAmountYuan(new BigDecimal("20.00"));
        order.setPayType(payType);
        order.setStatus(MembershipOrderStatus.PENDING_PAYMENT);
        order.setIdempotencyKey(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        order.setExpiresAt(OffsetDateTime.parse("2026-08-20T12:05:00Z"));
        order.setStateVersion(1L);
        order.setCreatedAt(OffsetDateTime.parse("2026-08-20T12:00:00Z"));
        order.setUpdatedAt(OffsetDateTime.parse("2026-08-20T12:00:00Z"));
        return order;
    }
}
