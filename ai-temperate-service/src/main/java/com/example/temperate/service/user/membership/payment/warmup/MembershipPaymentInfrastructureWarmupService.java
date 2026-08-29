package com.example.temperate.service.user.membership.payment.warmup;

/**
 * 该服务是来预热会员支付 Redis 连接和 Lua 脚本缓存，且保证整个过程不创建或修改任何业务 Key。
 */
public interface MembershipPaymentInfrastructureWarmupService {

    void warmUpRedisInfrastructure();
}
