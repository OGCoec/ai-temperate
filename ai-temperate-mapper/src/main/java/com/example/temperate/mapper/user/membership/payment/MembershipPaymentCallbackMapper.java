package com.example.temperate.mapper.user.membership.payment;

import com.example.temperate.model.user.membership.payment.MembershipPaymentCallback;
import com.example.temperate.model.user.membership.payment.MembershipPaymentCallbackWriteResult;
import com.example.temperate.model.user.membership.payment.MembershipPaymentRefundTerminalFact;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 该 Mapper 是来用一次 PostgreSQL 语句批量写入或解析会员支付回调，并返回每个输入的幂等归类。
 */
@Mapper
public interface MembershipPaymentCallbackMapper {

    List<MembershipPaymentCallbackWriteResult> batchInsertOrResolve(
            @Param("callbacksJson") String callbacksJson);

    List<MembershipPaymentCallback> findByIdsJsonForUpdate(
            @Param("idsJson") String idsJson);

    /** 批量读取退款恢复所需的回调和订单终态事实；缺少逻辑关联的输入不会伪造结果。 */
    List<MembershipPaymentRefundTerminalFact> findRefundTerminalFactsByIdsJson(
            @Param("idsJson") String idsJson);

    /** 统计固定半开用户 ID 区间关联的支付回调，供持久模板安全预检使用。 */
    int countByLoginIdentityIdRange(
            @Param("startInclusive") long startInclusive,
            @Param("endExclusive") long endExclusive);

    /** 对固定半开用户区间内的回调订单 ID 生成稳定摘要，供预热复位证明前序正式回调未被触碰。 */
    String hashOrderIdsByLoginIdentityIdRange(
            @Param("startInclusive") long startInclusive,
            @Param("endExclusive") long endExclusive);

    /** 只删除本轮精确订单清单关联的回调，模板用户及其他运行数据不在清理范围内。 */
    int deleteByOrderIdsJson(@Param("orderIdsJson") String orderIdsJson);

    int batchResolve(@Param("resolutionsJson") String resolutionsJson);
}
