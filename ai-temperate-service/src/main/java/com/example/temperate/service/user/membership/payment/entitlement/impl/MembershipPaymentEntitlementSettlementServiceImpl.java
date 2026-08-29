package com.example.temperate.service.user.membership.payment.entitlement.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.mapper.user.membership.payment.MembershipPaymentCallbackMapper;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderEntitlementResolution;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.MembershipPaymentCallback;
import com.example.temperate.model.user.membership.payment.MembershipPaymentCallbackResolution;
import com.example.temperate.service.user.membership.MembershipQuotaPlan;
import com.example.temperate.service.user.membership.MembershipQuotaPlanService;
import com.example.temperate.service.user.membership.payment.entitlement.MembershipPaymentEntitlementCommand;
import com.example.temperate.service.user.membership.payment.entitlement.MembershipPaymentEntitlementSettlementService;
import com.example.temperate.service.user.membership.payment.entitlement.MembershipPaymentRefundEntitlementCommand;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
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
 * 该实现是来按固定表顺序批量锁定订单、回调和额度，并让套餐发放与两个裁决字段在一个 PostgreSQL 事务中生效。
 *
 * <p>Redis PAID 只提供实时目标版本；数据库唯一约束与本事务仍是跨实例最终幂等边界，任何行数或事实冲突都会回滚并保留回调 marker。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentEntitlementSettlementServiceImpl
        implements MembershipPaymentEntitlementSettlementService {

    private static final int MAXIMUM_BATCH = 500;
    private static final String SUCCESS = "TRADE_SUCCESS";

    private final MembershipOrderMapper orderMapper;
    private final MembershipPaymentCallbackMapper callbackMapper;
    private final UserMembershipQuotaMapper quotaMapper;
    private final MembershipQuotaPlanService quotaPlanService;
    private final UserProfileCacheInvalidationExecutor cacheInvalidationExecutor;
    private final HybridBase64UrlCodec base64UrlCodec;
    private final ObjectMapper objectMapper;

    public MembershipPaymentEntitlementSettlementServiceImpl(
            MembershipOrderMapper orderMapper,
            MembershipPaymentCallbackMapper callbackMapper,
            UserMembershipQuotaMapper quotaMapper,
            MembershipQuotaPlanService quotaPlanService,
            UserProfileCacheInvalidationExecutor cacheInvalidationExecutor,
            HybridBase64UrlCodec base64UrlCodec,
            ObjectMapper objectMapper) {
        this.orderMapper = Objects.requireNonNull(orderMapper);
        this.callbackMapper = Objects.requireNonNull(callbackMapper);
        this.quotaMapper = Objects.requireNonNull(quotaMapper);
        this.quotaPlanService = Objects.requireNonNull(quotaPlanService);
        this.cacheInvalidationExecutor = Objects.requireNonNull(cacheInvalidationExecutor);
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /**
     * 锁顺序固定为订单、回调、额度；同一用户在一个批次中只能出现一次，否则无法确定哪笔新套餐应成为最终快照。
     */
    @Override
    @Transactional
    public void settleApplied(List<MembershipPaymentEntitlementCommand> commands) {
        List<MembershipPaymentEntitlementCommand> values = normalizedApplied(commands);
        if (values.isEmpty()) {
            return;
        }
        Map<String, MembershipOrder> orders = lockedOrders(values.stream()
                .map(command -> command.paidOrder().orderId())
                .toList());
        Map<String, MembershipPaymentCallback> callbacks = lockedCallbacks(values.stream()
                .map(MembershipPaymentEntitlementCommand::callbackId)
                .toList());

        List<MembershipPaymentEntitlementCommand> newGrants = new ArrayList<>();
        List<StateRow> stateRows = new ArrayList<>();
        for (MembershipPaymentEntitlementCommand command : values) {
            MembershipOrderSnapshot desired = command.paidOrder();
            MembershipOrder order = requiredOrder(orders, desired.orderId());
            MembershipPaymentCallback callback = requiredCallback(
                    callbacks, command.callbackId());
            validateAppliedFacts(command, order, callback);
            if (order.getStateVersion() < desired.stateVersion()) {
                stateRows.add(stateRow(desired));
            }
            if (order.getEntitlementResolution() == null) {
                newGrants.add(command);
            }
        }

        List<Long> grantUserIds = newGrants.stream()
                .map(command -> command.paidOrder().loginIdentityId())
                .sorted()
                .toList();
        Map<Long, UserMembershipQuota> quotas = lockedQuotas(grantUserIds);
        if (!stateRows.isEmpty()
                && orderMapper.batchAdvanceState(toJson(stateRows)) != stateRows.size()) {
            throw incomplete("Membership paid order state result is incomplete.");
        }
        if (!newGrants.isEmpty()) {
            List<GrantRow> grants = new ArrayList<>(newGrants.size());
            List<EntitlementRow> entitlements = new ArrayList<>(newGrants.size());
            for (MembershipPaymentEntitlementCommand command : newGrants) {
                MembershipOrderSnapshot paid = command.paidOrder();
                if (!quotas.containsKey(paid.loginIdentityId())) {
                    throw incomplete("Membership quota row is missing.");
                }
                MembershipQuotaPlan plan = quotaPlanService.getRequired(
                        paid.membershipTier());
                OffsetDateTime paidAt = utc(paid.paidAt());
                grants.add(new GrantRow(
                        paid.loginIdentityId(),
                        paid.membershipTier().ordinal(),
                        plan.totalMinor(),
                        null,
                        paidAt,
                        paidAt.plusMonths(1)));
                entitlements.add(new EntitlementRow(
                        idHex(paid.orderId()),
                        paid.providerTradeNo(),
                        MembershipOrderEntitlementResolution.APPLIED.name(),
                        utc(command.resolvedAt())));
            }
            if (quotaMapper.batchGrantPaidMemberships(toJson(grants)) != grants.size()) {
                throw incomplete("Membership quota grant result is incomplete.");
            }
            if (orderMapper.batchResolveEntitlements(toJson(entitlements))
                    != entitlements.size()) {
                throw incomplete("Membership entitlement result is incomplete.");
            }
        }
        List<CallbackResolutionRow> resolutions = values.stream()
                .map(command -> new CallbackResolutionRow(
                        idHex(command.callbackId()),
                        MembershipPaymentCallbackResolution.APPLIED.name(),
                        utc(command.resolvedAt())))
                .toList();
        if (callbackMapper.batchResolve(toJson(resolutions)) != resolutions.size()) {
            throw incomplete("Membership callback resolution result is incomplete.");
        }
        if (!grantUserIds.isEmpty()) {
            cacheInvalidationExecutor.evictAfterCommit(grantUserIds);
        }
    }

    /** 退款资格必须先同时写入订单与回调，事务提交后 Callback Worker 才能调用 BAR 幂等退款。 */
    @Override
    @Transactional
    public void settleRefundRequired(
            List<MembershipPaymentRefundEntitlementCommand> commands) {
        List<MembershipPaymentRefundEntitlementCommand> values =
                normalizedRefunds(commands);
        if (values.isEmpty()) {
            return;
        }
        Map<String, MembershipOrder> orders = lockedOrders(values.stream()
                .map(MembershipPaymentRefundEntitlementCommand::orderId)
                .toList());
        Map<String, MembershipPaymentCallback> callbacks = lockedCallbacks(values.stream()
                .map(MembershipPaymentRefundEntitlementCommand::callbackId)
                .toList());
        List<EntitlementRow> entitlements = new ArrayList<>(values.size());
        List<CallbackResolutionRow> resolutions = new ArrayList<>(values.size());
        for (MembershipPaymentRefundEntitlementCommand command : values) {
            MembershipOrder order = requiredOrder(orders, command.orderId());
            MembershipPaymentCallback callback = requiredCallback(
                    callbacks, command.callbackId());
            if (!Arrays.equals(order.getId(), callback.getOrderId())
                    || !Arrays.equals(order.getId(), base64UrlCodec.decode(command.orderId()))
                    || (order.getEntitlementResolution() != null
                    && order.getEntitlementResolution()
                    != MembershipOrderEntitlementResolution.REFUND_REQUIRED
                    && order.getEntitlementResolution()
                    != MembershipOrderEntitlementResolution.NOT_GRANTED)
                    || (callback.getResolution() != null
                    && !MembershipPaymentCallbackResolution.REFUND_REQUIRED.name()
                    .equals(callback.getResolution()))) {
                throw incomplete("Membership refund entitlement facts conflict.");
            }
            // 回调表保留已验真的供应商交易事实并继续作为退款依据；订单进入退款终态后必须清空流水号，
            // 避免把“已支付但未授予权益”的外部事实误表示成订单已成功绑定供应商交易。
            entitlements.add(new EntitlementRow(
                    idHex(command.orderId()),
                    null,
                    MembershipOrderEntitlementResolution.REFUND_REQUIRED.name(),
                    utc(command.resolvedAt())));
            resolutions.add(new CallbackResolutionRow(
                    idHex(command.callbackId()),
                    MembershipPaymentCallbackResolution.REFUND_REQUIRED.name(),
                    utc(command.resolvedAt())));
        }
        if (orderMapper.batchResolveEntitlements(toJson(entitlements))
                != entitlements.size()
                || callbackMapper.batchResolve(toJson(resolutions))
                != resolutions.size()) {
            throw incomplete("Membership refund resolution result is incomplete.");
        }
    }

    private void validateAppliedFacts(
            MembershipPaymentEntitlementCommand command,
            MembershipOrder order,
            MembershipPaymentCallback callback) {
        MembershipOrderSnapshot desired = command.paidOrder();
        if (!Arrays.equals(order.getId(), callback.getOrderId())
                || !Objects.equals(order.getLoginIdentityId(), desired.loginIdentityId())
                || order.getMembershipTier() != desired.membershipTier()
                || !sameAmount(order.getPayAmountYuan(), desired.payAmountYuan())
                || !Objects.equals(order.getPayType(), desired.payType())
                || !Objects.equals(order.getIdempotencyKey(), desired.idempotencyKey())
                || !sameInstant(order.getPaymentStartedAt(), desired.paymentStartedAt())
                || !sameInstant(order.getExpiresAt(), desired.expiresAt())
                || !Objects.equals(callback.getProviderTradeNo(), desired.providerTradeNo())
                || !Objects.equals(callback.getTradeStatus(), SUCCESS)
                || !sameAmount(callback.getPaidAmountYuan(), desired.payAmountYuan())
                || !sameInstant(callback.getPaidAt(), desired.paidAt())
                || order.getStateVersion() > desired.stateVersion()
                || (order.getEntitlementResolution() != null
                && order.getEntitlementResolution()
                != MembershipOrderEntitlementResolution.APPLIED)
                || (callback.getResolution() != null
                && !MembershipPaymentCallbackResolution.APPLIED.name()
                .equals(callback.getResolution()))) {
            throw incomplete("Membership applied entitlement facts conflict.");
        }
        if (order.getStateVersion() == desired.stateVersion()) {
            if (order.getStatus() != MembershipOrderStatus.PAID
                    || !Objects.equals(
                    order.getProviderTradeNo(), desired.providerTradeNo())
                    || !sameInstant(order.getPaidAt(), desired.paidAt())
                    || !sameInstant(
                    order.getClosingDeadlineAt(), desired.closingDeadlineAt())) {
                throw incomplete("Membership paid order version facts conflict.");
            }
            return;
        }
        if (order.getStatus() != MembershipOrderStatus.PENDING_PAYMENT
                && order.getStatus() != MembershipOrderStatus.CLOSING) {
            throw incomplete("Membership paid order cannot advance from its database state.");
        }
        if (order.getProviderTradeNo() != null
                && !order.getProviderTradeNo().equals(desired.providerTradeNo())) {
            throw incomplete("Membership provider trade binding conflicts.");
        }
    }

    private Map<String, MembershipOrder> lockedOrders(List<String> orderIds) {
        List<String> distinct = orderIds.stream().distinct().sorted().toList();
        List<MembershipOrder> rows = orderMapper.findByIdsJsonForUpdate(
                toJson(distinct.stream().map(this::idHex).toList()));
        if (rows == null || rows.size() != distinct.size()) {
            throw incomplete("Membership order lock result is incomplete.");
        }
        Map<String, MembershipOrder> result = new LinkedHashMap<>();
        for (MembershipOrder row : rows) {
            result.put(base64UrlCodec.encode(row.getId()), row);
        }
        return result;
    }

    private Map<String, MembershipPaymentCallback> lockedCallbacks(
            List<String> callbackIds) {
        List<String> distinct = callbackIds.stream().distinct().sorted().toList();
        List<MembershipPaymentCallback> rows = callbackMapper.findByIdsJsonForUpdate(
                toJson(distinct.stream().map(this::idHex).toList()));
        if (rows == null || rows.size() != distinct.size()) {
            throw incomplete("Membership callback lock result is incomplete.");
        }
        Map<String, MembershipPaymentCallback> result = new LinkedHashMap<>();
        for (MembershipPaymentCallback row : rows) {
            result.put(base64UrlCodec.encode(row.getId()), row);
        }
        return result;
    }

    private Map<Long, UserMembershipQuota> lockedQuotas(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<UserMembershipQuota> rows = quotaMapper.findByLoginIdentityIdsForUpdate(userIds);
        if (rows == null || rows.size() != userIds.size()) {
            throw incomplete("Membership quota lock result is incomplete.");
        }
        Map<Long, UserMembershipQuota> result = new LinkedHashMap<>();
        for (UserMembershipQuota row : rows) {
            result.put(row.getLoginIdentityId(), row);
        }
        if (result.size() != userIds.size()) {
            throw incomplete("Membership quota lock result contains duplicates.");
        }
        return result;
    }

    private MembershipOrder requiredOrder(
            Map<String, MembershipOrder> orders,
            String orderId) {
        MembershipOrder order = orders.get(orderId);
        if (order == null) {
            throw incomplete("Membership order is missing.");
        }
        return order;
    }

    private MembershipPaymentCallback requiredCallback(
            Map<String, MembershipPaymentCallback> callbacks,
            String callbackId) {
        MembershipPaymentCallback callback = callbacks.get(callbackId);
        if (callback == null) {
            throw incomplete("Membership callback is missing.");
        }
        return callback;
    }

    private List<MembershipPaymentEntitlementCommand> normalizedApplied(
            List<MembershipPaymentEntitlementCommand> commands) {
        List<MembershipPaymentEntitlementCommand> values = List.copyOf(
                Objects.requireNonNull(commands));
        validateBatch(values.size());
        if (values.stream().map(MembershipPaymentEntitlementCommand::callbackId)
                        .distinct().count() != values.size()
                || values.stream().map(command -> command.paidOrder().orderId())
                        .distinct().count() != values.size()
                || values.stream().map(command -> command.paidOrder().loginIdentityId())
                        .distinct().count() != values.size()) {
            throw new IllegalArgumentException(
                    "Applied entitlement batch contains duplicate facts.");
        }
        return values.stream()
                .sorted(Comparator.comparing(command -> command.paidOrder().orderId()))
                .toList();
    }

    private List<MembershipPaymentRefundEntitlementCommand> normalizedRefunds(
            List<MembershipPaymentRefundEntitlementCommand> commands) {
        List<MembershipPaymentRefundEntitlementCommand> values = List.copyOf(
                Objects.requireNonNull(commands));
        validateBatch(values.size());
        if (values.stream().map(MembershipPaymentRefundEntitlementCommand::callbackId)
                        .distinct().count() != values.size()
                || values.stream().map(MembershipPaymentRefundEntitlementCommand::orderId)
                        .distinct().count() != values.size()) {
            throw new IllegalArgumentException(
                    "Refund entitlement batch contains duplicate facts.");
        }
        return values.stream()
                .sorted(Comparator.comparing(
                        MembershipPaymentRefundEntitlementCommand::orderId))
                .toList();
    }

    private static void validateBatch(int size) {
        if (size > MAXIMUM_BATCH) {
            throw new IllegalArgumentException(
                    "Membership entitlement batch cannot exceed 500 rows.");
        }
    }

    private String idHex(String publicId) {
        return HexFormat.of().formatHex(base64UrlCodec.decode(publicId));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new MembershipPaymentInfrastructureException(
                    "Membership entitlement serialization failed.", exception);
        }
    }

    private StateRow stateRow(MembershipOrderSnapshot snapshot) {
        return new StateRow(
                idHex(snapshot.orderId()),
                snapshot.status().code(),
                snapshot.providerTradeNo(),
                snapshot.paymentStartedAt(),
                snapshot.closingDeadlineAt(),
                snapshot.paidAt(),
                snapshot.stateVersion(),
                snapshot.updatedAt());
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static boolean sameAmount(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private static boolean sameInstant(
            OffsetDateTime left,
            OffsetDateTime right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.toInstant().equals(right.toInstant());
    }

    private static MembershipPaymentInfrastructureException incomplete(
            String message) {
        return new MembershipPaymentInfrastructureException(message);
    }

    /** 该 JSON 行只承载已有订单批量单调推进所需字段。 */
    private record StateRow(
            String idHex,
            int status,
            String providerTradeNo,
            OffsetDateTime paymentStartedAt,
            OffsetDateTime closingDeadlineAt,
            OffsetDateTime paidAt,
            long stateVersion,
            OffsetDateTime updatedAt) {
    }

    /** 该 JSON 行把新套餐完整额度与尚未激活的周期哨兵一起写入。 */
    private record GrantRow(
            long loginIdentityId,
            int membershipTier,
            long quotaBalanceMinor,
            OffsetDateTime quotaPeriodStartedAt,
            OffsetDateTime quotaPeriodEndsAt,
            OffsetDateTime membershipExpiresAt) {
    }

    /** 该 JSON 行同时保存权益裁决与已验真的第三方流水号，用于在同一本地事务中闭合订单审计事实。 */
    private record EntitlementRow(
            String orderIdHex,
            String providerTradeNo,
            String resolution,
            OffsetDateTime resolvedAt) {
    }

    /** 该 JSON 行复用现有回调批量裁决协议。 */
    private record CallbackResolutionRow(
            String callbackIdHex,
            String resolution,
            OffsetDateTime resolvedAt) {
    }
}
