package com.example.temperate.service.user.membership.payment.loadtest;

import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackCompletion;
import java.util.Collection;

/**
 * 该服务是来为真实链路恢复测试武装一次性 callback complete 故障，并提供单调触发计数作为验收证据。
 *
 * <p>它不改变数据库或 Redis 事实；未武装时调用为无副作用快速返回，且只有 loadtest 控制入口可以武装。</p>
 */
public interface MembershipPaymentLoadtestFaultGate {

    long armCallbackCompleteFailure(String orderId);

    void failBeforeCallbackCompleteIfArmed(
            Collection<PaymentCallbackCompletion> completions);

    long callbackCompleteFailureCount();
}
