package com.example.temperate.service.user.membership.payment.persistence;

/**
 * 该服务是来执行一次有界订单 dirty/processing 刷盘轮次，并以 Redisson 看门狗锁协调多实例调度。
 */
public interface MembershipOrderBatchPersistenceService {

    void flushOneRun();
}
