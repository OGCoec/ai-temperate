package com.example.temperate.service.user.membership.payment.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.order.impl.MembershipPaymentAttemptServiceImpl;
import com.example.temperate.service.user.membership.payment.order.impl.MembershipPaymentAttemptTransactionServiceImpl;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 该单元测试是来锁定支付发起的首次写入、有效期内重放、过期拒绝以及数据库提交后 Redis 刷新语义。
 */
final class MembershipPaymentAttemptServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:04:59Z");
    private static final OffsetDateTime NOW_OFFSET =
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final byte[] ORDER_ID = id((byte) 9);

    @Test
    void transactionReportsFirstAtomicWriteAndPreservesIncrementedVersion() {
        MembershipOrderMapper mapper = mock(MembershipOrderMapper.class);
        MembershipOrder started = order(NOW_OFFSET, NOW_OFFSET.plusSeconds(1));
        started.setStateVersion(2L);
        when(mapper.startPaymentAttemptIfAbsent(
                any(), anyLong(), any(), any())).thenReturn(started);
        MembershipPaymentAttemptTransactionServiceImpl service =
                new MembershipPaymentAttemptTransactionServiceImpl(mapper);

        MembershipPaymentAttemptDatabaseResult result = service.startOrGet(
                17L, ORDER_ID, NOW_OFFSET);

        assertThat(result.started()).isTrue();
        assertThat(result.order().getPaymentStartedAt()).isEqualTo(NOW_OFFSET);
        assertThat(result.order().getStateVersion()).isEqualTo(2L);
    }

    @Test
    void transactionReturnsOriginalStartTimeForReplayWithinValidityWindow() {
        MembershipOrderMapper mapper = mock(MembershipOrderMapper.class);
        OffsetDateTime original = NOW_OFFSET.minusSeconds(30);
        MembershipOrder persisted = order(original, NOW_OFFSET.plusSeconds(1));
        when(mapper.findOwnedById(ORDER_ID, 17L)).thenReturn(persisted);
        MembershipPaymentAttemptTransactionServiceImpl service =
                new MembershipPaymentAttemptTransactionServiceImpl(mapper);

        MembershipPaymentAttemptDatabaseResult result = service.startOrGet(
                17L, ORDER_ID, NOW_OFFSET);

        assertThat(result.started()).isFalse();
        assertThat(result.order().getPaymentStartedAt()).isEqualTo(original);
    }

    @Test
    void transactionRejectsExactExpiryAndTerminalStates() {
        MembershipOrderMapper mapper = mock(MembershipOrderMapper.class);
        MembershipOrder expired = order(NOW_OFFSET.minusSeconds(1), NOW_OFFSET);
        when(mapper.findOwnedById(ORDER_ID, 17L)).thenReturn(expired);
        MembershipPaymentAttemptTransactionServiceImpl service =
                new MembershipPaymentAttemptTransactionServiceImpl(mapper);

        assertThatThrownBy(() -> service.startOrGet(17L, ORDER_ID, NOW_OFFSET))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT));

        expired.setExpiresAt(NOW_OFFSET.plusMinutes(1));
        expired.setStatus(MembershipOrderStatus.CLOSING);
        assertThatThrownBy(() -> service.startOrGet(17L, ORDER_ID, NOW_OFFSET))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT));
    }

    @Test
    void orchestratorRefreshesRedisAfterDatabaseResultAndReturnsCurrentVersion() {
        MembershipPaymentAttemptTransactionService transactionService =
                mock(MembershipPaymentAttemptTransactionService.class);
        MembershipOrderSnapshotStore snapshotStore = mock(MembershipOrderSnapshotStore.class);
        MembershipOrder order = order(NOW_OFFSET, NOW_OFFSET.plusSeconds(1));
        order.setStateVersion(2L);
        when(transactionService.startOrGet(17L, ORDER_ID, NOW_OFFSET))
                .thenReturn(new MembershipPaymentAttemptDatabaseResult(order, true));
        when(snapshotStore.find(any())).thenReturn(Optional.empty());
        MembershipPaymentAttemptServiceImpl service = new MembershipPaymentAttemptServiceImpl(
                transactionService,
                snapshotStore,
                new HybridBase64UrlCodec(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        MembershipPaymentAttemptResult result = service.start(17L, ORDER_ID);

        assertThat(result.started()).isTrue();
        assertThat(result.snapshot().paymentStartedAt()).isEqualTo(NOW_OFFSET);
        assertThat(result.snapshot().stateVersion()).isEqualTo(2L);
        verify(snapshotStore).put(result.snapshot());
    }

    @Test
    void orchestratorRejectsWhenRedisAlreadyContainsNewerPaidState() {
        MembershipPaymentAttemptTransactionService transactionService =
                mock(MembershipPaymentAttemptTransactionService.class);
        MembershipOrderSnapshotStore snapshotStore = mock(MembershipOrderSnapshotStore.class);
        MembershipOrder databaseOrder = order(NOW_OFFSET.minusSeconds(30), NOW_OFFSET.plusMinutes(1));
        databaseOrder.setStateVersion(2L);
        when(transactionService.startOrGet(17L, ORDER_ID, NOW_OFFSET))
                .thenReturn(new MembershipPaymentAttemptDatabaseResult(databaseOrder, false));
        MembershipOrderSnapshot paidSnapshot = new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                new HybridBase64UrlCodec().encode(ORDER_ID),
                17L,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                "alipay",
                MembershipOrderStatus.PAID,
                databaseOrder.getIdempotencyKey(),
                "provider-trade-paid",
                databaseOrder.getPaymentStartedAt(),
                databaseOrder.getExpiresAt(),
                null,
                NOW_OFFSET,
                3L,
                databaseOrder.getCreatedAt(),
                NOW_OFFSET);
        when(snapshotStore.find(any())).thenReturn(Optional.of(paidSnapshot));
        MembershipPaymentAttemptServiceImpl service = new MembershipPaymentAttemptServiceImpl(
                transactionService,
                snapshotStore,
                new HybridBase64UrlCodec(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.start(17L, ORDER_ID))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT));
    }

    @Test
    void orchestratorDoesNotMutateDatabaseWhenRedisAlreadyContainsCancelledState() {
        MembershipPaymentAttemptTransactionService transactionService =
                mock(MembershipPaymentAttemptTransactionService.class);
        MembershipOrderSnapshotStore snapshotStore = mock(MembershipOrderSnapshotStore.class);
        MembershipOrder databaseOrder = order(null, NOW_OFFSET.plusMinutes(1));
        when(snapshotStore.find(new HybridBase64UrlCodec().encode(ORDER_ID)))
                .thenReturn(Optional.of(snapshotWithStatus(
                        databaseOrder,
                        MembershipOrderStatus.CANCELLED,
                        2L)));
        MembershipPaymentAttemptServiceImpl service = new MembershipPaymentAttemptServiceImpl(
                transactionService,
                snapshotStore,
                new HybridBase64UrlCodec(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.start(17L, ORDER_ID))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT));
        verifyNoInteractions(transactionService);
    }

    private static MembershipOrderSnapshot snapshotWithStatus(
            MembershipOrder databaseOrder,
            MembershipOrderStatus status,
            long stateVersion) {
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                new HybridBase64UrlCodec().encode(ORDER_ID),
                17L,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                "alipay",
                status,
                databaseOrder.getIdempotencyKey(),
                status == MembershipOrderStatus.PAID ? "provider-trade-paid" : null,
                databaseOrder.getPaymentStartedAt(),
                databaseOrder.getExpiresAt(),
                null,
                status == MembershipOrderStatus.PAID ? NOW_OFFSET : null,
                stateVersion,
                databaseOrder.getCreatedAt(),
                NOW_OFFSET);
    }

    private static MembershipOrder order(
            OffsetDateTime paymentStartedAt,
            OffsetDateTime expiresAt) {
        MembershipOrder order = new MembershipOrder();
        order.setId(ORDER_ID);
        order.setLoginIdentityId(17L);
        order.setMembershipTier(MembershipTier.PLUS);
        order.setPayAmountYuan(new BigDecimal("20.00"));
        order.setPayType("alipay");
        order.setStatus(MembershipOrderStatus.PENDING_PAYMENT);
        order.setIdempotencyKey(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        order.setPaymentStartedAt(paymentStartedAt);
        order.setExpiresAt(expiresAt);
        order.setStateVersion(1L);
        order.setCreatedAt(NOW_OFFSET.minusMinutes(5));
        order.setUpdatedAt(paymentStartedAt);
        return order;
    }

    private static byte[] id(byte value) {
        byte[] id = new byte[16];
        Arrays.fill(id, value);
        return id;
    }
}
