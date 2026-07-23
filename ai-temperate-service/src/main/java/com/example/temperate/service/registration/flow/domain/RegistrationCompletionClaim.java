package com.example.temperate.service.registration.flow.domain;

import com.example.temperate.common.security.hmac.HmacIdentifier;

/**
 * 表示完成注册时对一次性流程状态的领取结果。
 *
 * <p>领取标识用于将提交后的消费或回滚释放绑定到同一请求，防止并发完成请求重复创建账号。</p>
 */
public record RegistrationCompletionClaim(
        RegistrationFlowSnapshot snapshot,
        HmacIdentifier claimId) {
}
