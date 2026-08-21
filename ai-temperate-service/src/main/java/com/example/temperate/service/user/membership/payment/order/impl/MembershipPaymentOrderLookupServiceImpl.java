package com.example.temperate.service.user.membership.payment.order.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentOrderLookupService;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来为单条 RabbitMQ 检查执行 Redis 优先、数据库兜底读取，并避免数据库旧版本覆盖实时快照。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentOrderLookupServiceImpl
        implements MembershipPaymentOrderLookupService {

    private final MembershipOrderSnapshotStore snapshotStore;
    private final MembershipOrderMapper orderMapper;
    private final HybridBase64UrlCodec base64UrlCodec;

    public MembershipPaymentOrderLookupServiceImpl(
            MembershipOrderSnapshotStore snapshotStore,
            MembershipOrderMapper orderMapper,
            HybridBase64UrlCodec base64UrlCodec) {
        this.snapshotStore = Objects.requireNonNull(snapshotStore);
        this.orderMapper = Objects.requireNonNull(orderMapper);
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
    }

    @Override
    public Optional<MembershipOrderSnapshot> find(String orderId) {
        byte[] internalId = base64UrlCodec.decode(orderId);
        String canonical = base64UrlCodec.encode(internalId);
        Optional<MembershipOrderSnapshot> cached = snapshotStore.find(canonical);
        if (cached.isPresent()) {
            return cached;
        }
        MembershipOrder persisted = orderMapper.findById(internalId);
        if (persisted == null) {
            return Optional.empty();
        }
        MembershipOrderSnapshot databaseSnapshot = toSnapshot(persisted);
        if (databaseSnapshot.status().terminal()) {
            return Optional.of(databaseSnapshot);
        }
        snapshotStore.put(databaseSnapshot);
        return snapshotStore.find(canonical).or(() -> Optional.of(databaseSnapshot));
    }

    private MembershipOrderSnapshot toSnapshot(MembershipOrder order) {
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                base64UrlCodec.encode(order.getId()),
                order.getLoginIdentityId(),
                order.getMembershipTier(),
                order.getPayAmountYuan(),
                order.getPayType(),
                order.getStatus(),
                order.getIdempotencyKey(),
                order.getProviderTradeNo(),
                order.getPaymentStartedAt(),
                order.getExpiresAt(),
                order.getClosingDeadlineAt(),
                order.getPaidAt(),
                order.getStateVersion(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
