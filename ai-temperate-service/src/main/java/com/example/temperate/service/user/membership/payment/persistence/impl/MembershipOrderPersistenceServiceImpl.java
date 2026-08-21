package com.example.temperate.service.user.membership.payment.persistence.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.persistence.MembershipOrderPersistenceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 该实现是来在单个 PostgreSQL 本地事务中序列化每个订单最高版本，并调用一次条件批量 UPDATE 防止旧快照覆盖新状态。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipOrderPersistenceServiceImpl
        implements MembershipOrderPersistenceService {

    private static final int MAXIMUM_BATCH = 500;

    private final MembershipOrderMapper orderMapper;
    private final HybridBase64UrlCodec base64UrlCodec;
    private final ObjectMapper objectMapper;

    public MembershipOrderPersistenceServiceImpl(
            MembershipOrderMapper orderMapper,
            HybridBase64UrlCodec base64UrlCodec,
            ObjectMapper objectMapper) {
        this.orderMapper = Objects.requireNonNull(orderMapper);
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /** 同版本重试影响零行属于幂等成功；事务提交后才能由调用方完成 processing 令牌。 */
    @Override
    @Transactional
    public void persist(List<MembershipOrderSnapshot> snapshots) {
        List<MembershipOrderSnapshot> values = highestVersions(snapshots);
        if (values.isEmpty()) {
            return;
        }
        List<OrderStateRow> rows = new ArrayList<>(values.size());
        for (MembershipOrderSnapshot snapshot : values) {
            rows.add(new OrderStateRow(
                    HexFormat.of().formatHex(base64UrlCodec.decode(snapshot.orderId())),
                    snapshot.status().code(),
                    snapshot.providerTradeNo(),
                    snapshot.paymentStartedAt(),
                    snapshot.closingDeadlineAt(),
                    snapshot.paidAt(),
                    snapshot.stateVersion(),
                    snapshot.updatedAt()));
        }
        orderMapper.batchAdvanceState(toJson(rows));
    }

    private static List<MembershipOrderSnapshot> highestVersions(
            List<MembershipOrderSnapshot> snapshots) {
        Objects.requireNonNull(snapshots, "snapshots must not be null");
        Map<String, MembershipOrderSnapshot> highest = new LinkedHashMap<>();
        for (MembershipOrderSnapshot snapshot : snapshots) {
            MembershipOrderSnapshot value = Objects.requireNonNull(snapshot);
            highest.merge(
                    value.orderId(),
                    value,
                    (left, right) -> Comparator
                            .comparingLong(MembershipOrderSnapshot::stateVersion)
                            .compare(left, right) >= 0 ? left : right);
        }
        if (highest.size() > MAXIMUM_BATCH) {
            throw new IllegalArgumentException(
                    "Membership order persistence batch exceeds 500 orders.");
        }
        return List.copyOf(highest.values());
    }

    private String toJson(List<OrderStateRow> rows) {
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (JsonProcessingException exception) {
            throw new MembershipPaymentInfrastructureException(
                    "Membership order persistence serialization failed.", exception);
        }
    }

    /** 该载荷与 MyBatis JSONB recordset 字段严格对应，金额与用户信息不参与状态刷盘。 */
    private record OrderStateRow(
            String idHex,
            int status,
            String providerTradeNo,
            OffsetDateTime paymentStartedAt,
            OffsetDateTime closingDeadlineAt,
            OffsetDateTime paidAt,
            long stateVersion,
            OffsetDateTime updatedAt) {
    }
}
