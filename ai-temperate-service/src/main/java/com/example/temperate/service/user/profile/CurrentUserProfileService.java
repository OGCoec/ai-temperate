package com.example.temperate.service.user.profile;

/**
 * 提供当前已认证用户的最小个人资料读取边界，并将账号已删除或禁用统一映射为会话不可用错误。
 */
public interface CurrentUserProfileService {

    /**
     * 按安全上下文已经确认的内部用户 ID 查询个人中心展示资料。
     *
     * @param userId 已认证的内部用户 ID
     * @return 可展示的账号、会员与额度资料
     */
    CurrentUserProfileResult getRequired(long userId);
}
