package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.model.user.membership.payment.MembershipPaymentCallbackWriteResult;
import com.example.temperate.model.user.membership.payment.MembershipPaymentRefundTerminalFact;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 该事务服务是来用一次 PostgreSQL 批量语句写入或解析已校验回调，并在提交后返回逐项最终幂等结果。
 */
public interface PaymentCallbackPersistenceService {

    List<MembershipPaymentCallbackWriteResult> persist(
            List<PaymentCallbackSnapshot> callbacks);

    void resolve(List<PaymentCallbackResolutionCommand> resolutions);

    Map<String, MembershipPaymentRefundTerminalFact> findRefundTerminalFacts(
            Collection<String> callbackIds);
}
