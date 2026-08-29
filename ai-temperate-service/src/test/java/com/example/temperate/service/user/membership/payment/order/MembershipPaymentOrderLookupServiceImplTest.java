package com.example.temperate.service.user.membership.payment.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.order.impl.MembershipPaymentOrderLookupServiceImpl;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCoordinator;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * 该单元测试是来约束 RabbitMQ 订单查询优先读取 Redis、缺失时才回源数据库，并由原子 putAndGet 直接返回实时赢家。
 */
final class MembershipPaymentOrderLookupServiceImplTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-08-20T12:00:00Z");
    private static final byte[] ORDER_BYTES = bytes((byte) 6);
    private static final HybridBase64UrlCodec CODEC = new HybridBase64UrlCodec();
    private static final String ORDER_ID = CODEC.encode(ORDER_BYTES);

    private MembershipOrderSnapshotStore snapshotStore;
    private MembershipOrderSnapshotWriteCoordinator snapshotWriteCoordinator;
    private MembershipOrderMapper orderMapper;
    private MembershipPaymentOrderLookupService service;

    @BeforeEach
    void setUp() {
        snapshotStore = mock(MembershipOrderSnapshotStore.class);
        snapshotWriteCoordinator = mock(MembershipOrderSnapshotWriteCoordinator.class);
        orderMapper = mock(MembershipOrderMapper.class);
        service = new MembershipPaymentOrderLookupServiceImpl(
                snapshotStore, snapshotWriteCoordinator, orderMapper, CODEC);
    }

    @Test
    void redisHitReturnsRealtimeSnapshotWithoutReadingDatabase() {
        MembershipOrderSnapshot cached = snapshot(MembershipOrderStatus.CLOSING, 2L);
        when(snapshotStore.find(ORDER_ID)).thenReturn(Optional.of(cached));

        assertThat(service.find(ORDER_ID)).contains(cached);

        verify(orderMapper, never()).findById(any());
        verify(snapshotStore, never()).put(any());
        verify(snapshotWriteCoordinator, never()).putAndGet(any());
    }

    @Test
    void redisAndDatabaseMissReturnEmptyWithoutRebuildingCache() {
        when(snapshotStore.find(ORDER_ID)).thenReturn(Optional.empty());
        when(orderMapper.findById(any())).thenReturn(null);

        assertThat(service.find(ORDER_ID)).isEmpty();

        verify(orderMapper).findById(any());
        verify(snapshotStore, never()).put(any());
        verify(snapshotWriteCoordinator, never()).putAndGet(any());
    }

    @Test
    void nonterminalDatabaseFallbackRebuildsThenReturnsConcurrentRedisWinner() {
        MembershipOrder databaseOrder = order(MembershipOrderStatus.PENDING_PAYMENT, 1L);
        MembershipOrderSnapshot concurrent = snapshot(MembershipOrderStatus.CLOSING, 2L);
        when(snapshotStore.find(ORDER_ID)).thenReturn(Optional.empty());
        when(orderMapper.findById(any())).thenReturn(databaseOrder);
        when(snapshotWriteCoordinator.putAndGet(any())).thenReturn(concurrent);

        assertThat(service.find(ORDER_ID)).contains(concurrent);

        InOrder ordered = inOrder(snapshotStore, orderMapper, snapshotWriteCoordinator);
        ordered.verify(snapshotStore).find(ORDER_ID);
        ordered.verify(orderMapper).findById(any());
        ArgumentCaptor<MembershipOrderSnapshot> rebuilt =
                ArgumentCaptor.forClass(MembershipOrderSnapshot.class);
        ordered.verify(snapshotWriteCoordinator).putAndGet(rebuilt.capture());
        assertThat(rebuilt.getValue().status())
                .isEqualTo(MembershipOrderStatus.PENDING_PAYMENT);
        assertThat(rebuilt.getValue().stateVersion()).isEqualTo(1L);
        verify(snapshotStore).find(ORDER_ID);
    }

    @Test
    void terminalDatabaseFallbackDoesNotRebuildRedisSnapshot() {
        MembershipOrder paid = order(MembershipOrderStatus.PAID, 3L);
        when(snapshotStore.find(ORDER_ID)).thenReturn(Optional.empty());
        when(orderMapper.findById(any())).thenReturn(paid);

        assertThat(service.find(ORDER_ID)).get()
                .extracting(MembershipOrderSnapshot::status)
                .isEqualTo(MembershipOrderStatus.PAID);

        verify(snapshotStore, never()).put(any());
        verify(snapshotWriteCoordinator, never()).putAndGet(any());
    }

    private static MembershipOrder order(
            MembershipOrderStatus status,
            long stateVersion) {
        MembershipOrder order = new MembershipOrder();
        order.setId(ORDER_BYTES);
        order.setLoginIdentityId(17L);
        order.setMembershipTier(MembershipTier.PLUS);
        order.setPayAmountYuan(new BigDecimal("20.00"));
        order.setPayType("wxpay");
        order.setStatus(status);
        order.setIdempotencyKey(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        order.setProviderTradeNo("123456789");
        order.setExpiresAt(NOW.plusMinutes(5));
        order.setClosingDeadlineAt(
                status == MembershipOrderStatus.CLOSING ? NOW.plusMinutes(10) : null);
        order.setPaidAt(status == MembershipOrderStatus.PAID ? NOW : null);
        order.setStateVersion(stateVersion);
        order.setCreatedAt(NOW.minusMinutes(5));
        order.setUpdatedAt(NOW);
        return order;
    }

    private static MembershipOrderSnapshot snapshot(
            MembershipOrderStatus status,
            long stateVersion) {
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                ORDER_ID,
                17L,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                "wxpay",
                status,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                "123456789",
                null,
                NOW.plusMinutes(5),
                status == MembershipOrderStatus.CLOSING ? NOW.plusMinutes(10) : null,
                status == MembershipOrderStatus.PAID ? NOW : null,
                stateVersion,
                NOW.minusMinutes(5),
                NOW);
    }

    private static byte[] bytes(byte value) {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, value);
        return bytes;
    }
}
