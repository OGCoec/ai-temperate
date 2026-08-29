package com.example.temperate.service.user.membership.payment.persistence;

import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkerRunResult;

/**
 * 该服务是来执行一次有界订单 dirty/processing 刷盘轮次，并以 Redisson 看门狗锁协调多实例调度。
 */
public interface MembershipOrderBatchPersistenceService {

    MembershipPaymentWorkerRunResult flushOneRun();
}
