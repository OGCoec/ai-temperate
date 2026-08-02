package com.example.temperate.service.user.profile.cache;

import java.util.Collection;
import java.util.Optional;

/**
 * 定义当前用户资料快照的 Redis String 读取、尽力写入和显式失效边界。
 *
 * <p>读取与写入故障必须降级为缓存未命中，不能影响 PostgreSQL 权威读取；显式失效则向调用方传播异常，
 * 由事务提交后的有限重试机制负责收敛。</p>
 */
public interface UserProfileCacheStore {

    Optional<UserProfileCacheValue> find(long userId);

    void put(long userId, UserProfileCacheValue value);

    void evict(long userId);

    void evict(Collection<Long> userIds);
}
