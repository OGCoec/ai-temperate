package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkerRunResult;

/**
 * 该服务是来执行一次有界回调 ready/processing 收敛轮次，先落回调表再批量推进 Redis 订单状态。
 */
public interface PaymentCallbackBatchService {

    MembershipPaymentWorkerRunResult flushOneRun();
}
