package com.example.temperate.mapper.user.membership.payment;

import com.example.temperate.model.user.membership.payment.MembershipPaymentCallbackWriteResult;
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

    int batchResolve(@Param("resolutionsJson") String resolutionsJson);
}
