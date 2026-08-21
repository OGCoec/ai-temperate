package com.example.temperate.service.user.membership.payment.store;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackClaim;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackCompletion;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackEnqueueResult;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackSnapshot;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 该队列契约是来用 Redis ZSet 原子入队、领取、超时恢复和完成支付回调，不使用 Redis Stream。
 */
public interface PaymentCallbackQueue {

    PaymentCallbackEnqueueResult enqueue(
            PaymentCallbackSnapshot snapshot,
            HmacIdentifier fingerprint,
            HmacIdentifier providerTradeFingerprint);

    boolean ensureReady(String callbackId, long readyAtEpochMillis);

    long processingSize();

    List<PaymentCallbackClaim> claim(int maximum, long claimedAtEpochMillis);

    int recoverTimedOut(
            long cutoffEpochMillis,
            int maximum,
            long readyAtEpochMillis);

    Map<String, PaymentCallbackSnapshot> findAll(Collection<String> callbackIds);

    int requeue(Collection<PaymentCallbackClaim> claims, long readyAtEpochMillis);

    int complete(Collection<PaymentCallbackCompletion> completions);
}
