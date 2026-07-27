package com.example.temperate.service.admin.aimodel.icon.remote;

/**
 * 表示已通过 HTTPS、DNS、大小和真实图片内容验证的最终外部图标地址。
 */
public record ValidatedRemoteIcon(String finalUrl) {
}
