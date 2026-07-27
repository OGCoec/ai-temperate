package com.example.temperate.service.admin.aimodel.icon.image;

/**
 * 表示 SVG 图标验证时采用的安全策略档位。
 *
 * <p>严格档位用于本地上传和普通外链；可信官方档位只允许远程验证器在最终响应域名命中显式配置时使用，
 * 它只扩展受限样式和内嵌栅格图片能力，不绕过 XML、资源数量、尺寸或协议安全校验。</p>
 */
public enum AiModelIconSvgPolicyProfile {
    STRICT,
    TRUSTED_OFFICIAL
}
