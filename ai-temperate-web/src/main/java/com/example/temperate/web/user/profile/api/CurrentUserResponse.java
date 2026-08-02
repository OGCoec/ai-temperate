package com.example.temperate.web.user.profile.api;

import com.example.temperate.model.auth.enums.MembershipTier;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * 定义个人中心当前用户资料接口的最小响应，明确排除内部 ID、密码和令牌等安全字段。
 */
@Schema(description = "当前已认证用户可展示的最小个人资料")
public record CurrentUserResponse(
        @Schema(description = "用户展示名称", example = "Alice")
        String displayName,
        @Schema(description = "完整邮箱，仅返回给当前已认证用户", example = "a***@example.test")
        String email,
        @Schema(description = "E.164 手机号；未绑定时为 null", example = "+1415***0123", nullable = true)
        String phone,
        @Schema(
                description = "当前头像公开 URL；尚未设置头像时为 null",
                example = "https://ihaveaplan.oss-us-west-1.aliyuncs.com/ai-temperate/user/AAAAAAAAJxE/0123456789_abcdefghijklmnopqrstuvwxyz-.webp",
                nullable = true)
        String avatarUrl,
        @Schema(description = "当前会员等级", example = "FREE")
        MembershipTier membershipTier,
        @Schema(
                description = "页面展示使用的额度最小单位十进制字符串",
                example = "5000")
        String quotaBalanceMinor,
        @Schema(
                description = "按固定比例一百换算的额度十进制字符串",
                example = "50.0")
        String quotaBalance,
        @Schema(description = "当前套餐每周总额度最小单位十进制字符串", example = "5000")
        String quotaTotalMinor,
        @Schema(description = "当前套餐每周总额度十进制字符串", example = "50.0")
        String quotaTotal,
        @Schema(description = "当前周期已用额度最小单位十进制字符串", example = "800")
        String quotaUsedMinor,
        @Schema(description = "当前周期已用额度十进制字符串", example = "8.0")
        String quotaUsed,
        @Schema(description = "限制在零到一百且保留一位小数的使用率", example = "16.0")
        String quotaUsagePercent,
        @Schema(
                description = "当前周期实际开始时间；尚未开始首个周期时为 null",
                nullable = true)
        OffsetDateTime quotaPeriodStartedAt,
        @Schema(description = "当前有效周期终点或到期后的七天预计重置时间")
        OffsetDateTime quotaResetAt) {
}
