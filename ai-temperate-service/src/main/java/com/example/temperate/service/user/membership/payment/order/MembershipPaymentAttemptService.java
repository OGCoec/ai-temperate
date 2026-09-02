package com.example.temperate.service.user.membership.payment.order;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;

/**
 * 该服务是来记录首次支付发起事实、调用当前 Provider 创建支付入口，并返回短时浏览器提交描述。
 */
public interface MembershipPaymentAttemptService {

    MembershipPaymentAttemptResult start(
            long loginIdentityId,
            byte[] orderId,
            PaymentProviderType provider,
            String canonicalClientIp);
}
