package com.example.temperate.model.user.domain;

/**
 * 表示已认证用户在个人中心可以展示的最小资料，只承载展示名称、邮箱、E.164 手机号和当前头像 URL。
 *
 * <p>该边界有意排除内部用户 ID、密码哈希、令牌和请求审计信息，避免个人资料查询扩大敏感数据读取范围。</p>
 */
public record CurrentUserProfile(
        String displayName,
        String email,
        String phone,
        String avatarUrl) {
}
