package com.example.temperate.service.user.profile.cache;

import java.util.Collection;

/**
 * 定义用户资料数据库事务提交后的缓存失效调度边界。
 *
 * <p>调用方必须处于活动事务中；回滚事务不会删除缓存，提交成功后才执行有限次数 {@code UNLINK}。</p>
 */
public interface UserProfileCacheInvalidationExecutor {

    void evictAfterCommit(long userId);

    void evictAfterCommit(Collection<Long> userIds);
}
