package com.example.temperate.service.auth.identity.bloom.store;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceDecision;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceKind;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceMutationResult;
import com.example.temperate.service.auth.identity.bloom.ProtectedIdentityPresenceRecord;
import java.time.Duration;
import java.util.List;

/**
 * 定义身份 Bloom 状态机、分片计数器和构建租约的 Redis 持久化边界。
 */
public interface IdentityPresenceBloomStore {

    /**
     * 只在 ACTIVE 代次完整且参数匹配时读取计数器，否则返回不可用。
     */
    IdentityPresenceDecision check(
            IdentityPresenceKind kind, HmacIdentifier protectedIdentifier);

    /**
     * 以用户 ID 幂等凭据原子增加一条邮箱与可选手机号记录。
     */
    IdentityPresenceMutationResult add(ProtectedIdentityPresenceRecord record);

    /**
     * 在单次有界 Lua 中增加一批记录，任一计数器溢出时整批不修改。
     */
    IdentityPresenceMutationResult addAll(List<ProtectedIdentityPresenceRecord> records);

    /**
     * 原子删除一条已经由相同用户 ID 幂等凭据确认存在的邮箱与手机号计数。
     *
     * <p>本阶段只提供引擎能力，不接入账号删除或联系方式修改流程；未来调用方必须传入数据库提交前
     * 保存的精确旧值，禁止根据 Bloom 查询结果自行构造删除请求。</p>
     */
    IdentityPresenceMutationResult remove(ProtectedIdentityPresenceRecord record);

    /**
     * 在一次 Lua 中批量预检并删除计数，任一位置下溢时整批不修改。
     */
    IdentityPresenceMutationResult removeAll(List<ProtectedIdentityPresenceRecord> records);

    /**
     * 领取跨应用实例的构建租约，只有持有者可以创建新代次。
     */
    boolean tryAcquireBuildLease(String leaseToken, Duration ttl);

    boolean renewBuildLease(String leaseToken, Duration ttl);

    /**
     * 创建并登记 BUILDING 代次，返回切换后需要异步清理的旧 ACTIVE 代次。
     */
    String beginBuild(String generation);

    void markReady(String generation);

    /**
     * 通过 Lua 把完整 READY 代次原子切换为 ACTIVE。
     */
    void activate(String generation);

    void cleanupGeneration(String generation);

    void markDegraded(String reason);

    void releaseBuildLease(String leaseToken);
}
