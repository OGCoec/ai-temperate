package com.example.temperate.service.user.membership.payment.store;

import com.example.temperate.service.user.membership.payment.persistence.OrderPersistToken;
import java.util.Collection;
import java.util.List;

/**
 * 该队列契约是来领取 Redis 订单脏版本并在数据库提交后精确完成，旧令牌不能删除更新版本快照。
 */
public interface OrderPersistenceQueue {

    long dirtySize();

    long processingSize();

    List<OrderPersistToken> claim(int maximum, long claimedAtEpochMillis);

    int recoverTimedOut(
            long cutoffEpochMillis,
            int maximum,
            long readyAtEpochMillis);

    int requeue(Collection<OrderPersistToken> tokens, long readyAtEpochMillis);

    int complete(Collection<OrderPersistToken> tokens);
}
