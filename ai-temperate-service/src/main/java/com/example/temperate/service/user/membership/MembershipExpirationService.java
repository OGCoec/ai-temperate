package com.example.temperate.service.user.membership;

/**
 * 该服务是来在已认证请求进入业务前惰性处理付费会员到期，并返回本次请求是否实际完成降级。
 *
 * <p>它只消费数据库中的绝对到期时间，不负责购买、支付、续费或计算一个月订阅的开始时间。</p>
 */
public interface MembershipExpirationService {

    /**
     * 在当前 UTC 边界原子降级一个已经到期或缺失到期时间的付费会员；没有匹配记录时不写库。
     */
    boolean expireIfDue(long loginIdentityId);
}
