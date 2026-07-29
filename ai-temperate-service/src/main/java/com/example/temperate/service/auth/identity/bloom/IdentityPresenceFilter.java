package com.example.temperate.service.auth.identity.bloom;

/**
 * 为注册、登录和找回密码提供已注册邮箱与手机号的三态存在性判断。
 *
 * <p>该服务只把 Bloom 作为数据库查询前置优化；命中必须复核 PostgreSQL，未就绪或异常必须返回
 * UNAVAILABLE 以触发回源，绝不承担账号唯一性约束。</p>
 */
public interface IdentityPresenceFilter {

    /**
     * 查询已规范化小写邮箱；只有明确未命中时才允许调用方跳过 PostgreSQL。
     */
    IdentityPresenceDecision checkEmail(String normalizedEmail);

    /**
     * 查询已规范化 E.164 手机号；不可用或可能命中都必须回源 PostgreSQL。
     */
    IdentityPresenceDecision checkPhone(String normalizedE164Phone);

    /**
     * 在注册数据库事务提交后，以内部用户 ID 为幂等号原子增加邮箱和手机号计数。
     */
    IdentityPresenceMutationResult recordRegistration(
            long userId, String normalizedEmail, String normalizedE164Phone);

    /**
     * 记录 Bloom 可能命中经数据库复核后形成的假阳性，不改变过滤器内容。
     */
    void recordDatabaseVerification(
            IdentityPresenceKind kind,
            IdentityPresenceDecision decision,
            boolean databaseFound);

    /**
     * 把双版本全量构建调度到专用后台线程，不阻塞应用 Ready 事件。
     */
    void initializeInBackground();
}
