package com.example.temperate.service.user.membership.payment.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
 * 该单元测试是来约束订单创建正常路径只调用一次 createOrResolve，并保留幂等、强制替换和空返回回退裁决。
 */
final class MembershipOrderCreationTransactionServiceImplTest {

    @Test
    void candidateReturnedBySingleStatementMeansCreated() {
        MembershipOrderMapper mapper = mock(MembershipOrderMapper.class);
        MembershipOrder candidate = order(17L, MembershipTier.PLUS, "alipay");
        when(mapper.createOrResolve(candidate)).thenReturn(candidate);

        MembershipOrderCreationResult result =
                new MembershipOrderCreationTransactionServiceImpl(mapper)
                        .createOrGet(candidate);

        assertThat(result.created()).isTrue();
        verify(mapper).createOrResolve(candidate);
        verify(mapper, never()).insert(candidate);
        verify(mapper, never()).acquireCreationLock(17L);
    }

    @Test
    void sameIdempotencyWinnerReturnedBySingleStatementIsReplay() {
        MembershipOrderMapper mapper = mock(MembershipOrderMapper.class);
        MembershipOrder candidate = order(17L, MembershipTier.PLUS, "alipay");
        MembershipOrder winner = order(17L, MembershipTier.PLUS, "alipay");
        winner.setId(new byte[] {9, 8, 7, 6});
        when(mapper.createOrResolve(candidate)).thenReturn(winner);

        MembershipOrderCreationResult result =
                new MembershipOrderCreationTransactionServiceImpl(mapper)
                        .createOrGet(candidate);

        assertThat(result.created()).isFalse();
        assertThat(result.order()).isSameAs(winner);
        verify(mapper, never()).findByIdempotencyKey(candidate.getIdempotencyKey());
    }

    @Test
    void differentActiveOrderReturnedBySingleStatementRequiresReplacement() {
        MembershipOrderMapper mapper = mock(MembershipOrderMapper.class);
        MembershipOrder candidate = order(17L, MembershipTier.PRO, "wxpay");
        MembershipOrder active = order(17L, MembershipTier.PLUS, "alipay");
        active.setId(new byte[] {1, 2, 3, 4});
        active.setIdempotencyKey(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440001"));
        when(mapper.createOrResolve(candidate)).thenReturn(active);

        MembershipOrderCreationResult result =
                new MembershipOrderCreationTransactionServiceImpl(mapper)
                        .createOrGet(candidate);

        assertThat(result.created()).isFalse();
        assertThat(result.replacementRequired()).isTrue();
        assertThat(result.order()).isSameAs(active);
    }

    @Test
    void replacementClosesExpectedActiveOrderAndCreatesCandidateInOneTransaction() {
        MembershipOrderMapper mapper = mock(MembershipOrderMapper.class);
        MembershipOrder candidate = order(17L, MembershipTier.PRO, "wxpay");
        MembershipOrder active = order(17L, MembershipTier.PLUS, "alipay");
        active.setId(new byte[] {1, 2, 3, 4});
        active.setIdempotencyKey(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440001"));
        active.setPaymentStartedAt(OffsetDateTime.parse("2026-08-20T12:01:00Z"));
        when(mapper.findActiveByLoginIdentityId(17L)).thenReturn(active);
        when(mapper.supersedeActiveForReplacement(
                        active.getId(),
                        17L,
                        MembershipOrderStatus.CLOSED,
                        2L,
                        OffsetDateTime.parse("2026-08-20T12:02:00Z")))
                .thenReturn(1);
        when(mapper.insert(candidate)).thenReturn(1);
        MembershipOrderReplacementCommand command = new MembershipOrderReplacementCommand(
                candidate,
                active.getId(),
                MembershipOrderStatus.CLOSED,
                2L,
                OffsetDateTime.parse("2026-08-20T12:02:00Z"));

        MembershipOrderCreationResult result =
                new MembershipOrderCreationTransactionServiceImpl(mapper)
                        .replaceActive(command);

        assertThat(result.created()).isTrue();
        assertThat(result.replacementRequired()).isFalse();
        assertThat(result.order()).isSameAs(candidate);
        verify(mapper).acquireCreationLock(17L);
        verify(mapper).insert(candidate);
    }

    @Test
    void replacementAcceptsConservativeClosedDecisionFromNewerRedisPaymentFact() {
        MembershipOrderMapper mapper = mock(MembershipOrderMapper.class);
        MembershipOrder candidate = order(17L, MembershipTier.PRO, "wxpay");
        MembershipOrder active = order(17L, MembershipTier.PLUS, "alipay");
        active.setId(new byte[] {1, 2, 3, 4});
        active.setIdempotencyKey(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440001"));
        when(mapper.findActiveByLoginIdentityId(17L)).thenReturn(active);
        when(mapper.supersedeActiveForReplacement(
                        active.getId(),
                        17L,
                        MembershipOrderStatus.CLOSED,
                        2L,
                        OffsetDateTime.parse("2026-08-20T12:02:00Z")))
                .thenReturn(1);
        when(mapper.insert(candidate)).thenReturn(1);

        MembershipOrderCreationResult result =
                new MembershipOrderCreationTransactionServiceImpl(mapper)
                        .replaceActive(new MembershipOrderReplacementCommand(
                                candidate,
                                active.getId(),
                                MembershipOrderStatus.CLOSED,
                                2L,
                                OffsetDateTime.parse("2026-08-20T12:02:00Z")));

        assertThat(result.created()).isTrue();
        verify(mapper).supersedeActiveForReplacement(
                active.getId(),
                17L,
                MembershipOrderStatus.CLOSED,
                2L,
                OffsetDateTime.parse("2026-08-20T12:02:00Z"));
    }

    @Test
    void emptySingleStatementResultUsesExactIdempotencyFallback() {
        MembershipOrderMapper mapper = mock(MembershipOrderMapper.class);
        MembershipOrder candidate = order(17L, MembershipTier.PLUS, "alipay");
        MembershipOrder winner = order(17L, MembershipTier.PLUS, "alipay");
        winner.setId(new byte[] {9, 8, 7, 6});
        when(mapper.findByIdempotencyKey(candidate.getIdempotencyKey()))
                .thenReturn(winner);

        MembershipOrderCreationResult result =
                new MembershipOrderCreationTransactionServiceImpl(mapper)
                        .createOrGet(candidate);

        assertThat(result.created()).isFalse();
        assertThat(result.order()).isSameAs(winner);
        verify(mapper, never()).findActiveByLoginIdentityId(17L);
    }

    @Test
    void emptyResultWithoutWinnerReturnsControlledConflict() {
        MembershipOrderMapper mapper = mock(MembershipOrderMapper.class);
        MembershipOrder candidate = order(17L, MembershipTier.PLUS, "alipay");

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
