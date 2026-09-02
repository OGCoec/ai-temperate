package com.example.temperate.mapper.user.membership.payment;

import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.auth.enums.MembershipTier;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 该 Mapper 是来持久化会员支付订单，并以 JSONB 有界批量查询和单调版本更新避免逐条数据库 I/O。
 */
@Mapper
public interface MembershipOrderMapper {

    int insert(MembershipOrder order);

    MembershipOrder createOrResolve(MembershipOrder order);

    MembershipOrder findById(@Param("orderId") byte[] orderId);

    MembershipOrder findOwnedById(
            @Param("orderId") byte[] orderId,
            @Param("loginIdentityId") long loginIdentityId);

    MembershipOrder startPaymentAttemptIfAbsent(
            @Param("orderId") byte[] orderId,
            @Param("loginIdentityId") long loginIdentityId,
            @Param("pendingStatus") MembershipOrderStatus pendingStatus,
            @Param("startedAt") OffsetDateTime startedAt);

    MembershipOrder bindProviderTradeNoIfAbsent(
            @Param("orderId") byte[] orderId,
            @Param("loginIdentityId") long loginIdentityId,
            @Param("providerTradeNo") String providerTradeNo);

    MembershipOrder findByIdempotencyKey(
            @Param("idempotencyKey") UUID idempotencyKey);

    int acquireCreationLock(@Param("loginIdentityId") long loginIdentityId);

    MembershipOrder findActiveByLoginIdentityId(
            @Param("loginIdentityId") long loginIdentityId);

    int supersedeActiveForReplacement(
            @Param("orderId") byte[] orderId,
            @Param("loginIdentityId") long loginIdentityId,
            @Param("terminalStatus") MembershipOrderStatus terminalStatus,
            @Param("terminalStateVersion") long terminalStateVersion,
            @Param("changedAt") OffsetDateTime changedAt);

    MembershipOrder findLatestPaidOrder(
            @Param("loginIdentityId") long loginIdentityId,
            @Param("membershipTier") MembershipTier membershipTier);

    List<MembershipOrder> findByIdsJson(@Param("idsJson") String idsJson);

    List<MembershipOrder> findByIdsJsonForUpdate(@Param("idsJson") String idsJson);

    /** 统计固定半开用户 ID 区间内的订单，供持久测试模板安全预检使用。 */
    int countByLoginIdentityIdRange(
            @Param("startInclusive") long startInclusive,
            @Param("endExclusive") long endExclusive);

    /** 对固定半开用户区间内的订单 ID 生成稳定摘要，供区段预热复位前后校验既有正式事实未变化。 */
    String hashIdsByLoginIdentityIdRange(
            @Param("startInclusive") long startInclusive,
            @Param("endExclusive") long endExclusive);

    /** 只删除调用方已按本轮清单验证过的订单 ID，禁止使用用户区间做宽范围清理。 */
    int deleteByIdsJson(@Param("idsJson") String idsJson);

    int batchAdvanceState(@Param("snapshotsJson") String snapshotsJson);

    int batchResolveEntitlements(
            @Param("entitlementsJson") String entitlementsJson);
}
