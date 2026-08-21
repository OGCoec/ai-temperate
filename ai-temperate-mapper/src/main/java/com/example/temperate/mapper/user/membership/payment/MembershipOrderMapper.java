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

    MembershipOrder findById(@Param("orderId") byte[] orderId);

    MembershipOrder findOwnedById(
            @Param("orderId") byte[] orderId,
            @Param("loginIdentityId") long loginIdentityId);

    MembershipOrder startPaymentAttemptIfAbsent(
            @Param("orderId") byte[] orderId,
            @Param("loginIdentityId") long loginIdentityId,
            @Param("pendingStatus") MembershipOrderStatus pendingStatus,
            @Param("startedAt") OffsetDateTime startedAt);

    MembershipOrder findByIdempotencyKey(
            @Param("idempotencyKey") UUID idempotencyKey);

    MembershipOrder findLatestPaidOrder(
            @Param("loginIdentityId") long loginIdentityId,
            @Param("membershipTier") MembershipTier membershipTier,
            @Param("paidStatus") MembershipOrderStatus paidStatus);

    List<MembershipOrder> findByIdsJson(@Param("idsJson") String idsJson);

    int batchAdvanceState(@Param("snapshotsJson") String snapshotsJson);
}
