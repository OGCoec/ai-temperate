package com.example.temperate.web.admin.mailinspection;

import java.util.Objects;

/**
 * 表示已由 Spring Converter 校验并解码的邮箱检查任务公共 ID。
 *
 * <p>Controller 只接收该值对象，不直接执行 Base64URL 解码；内部 Long 不会进入响应。</p>
 */
public record MailInspectionJobPublicId(String value, long internalId) {

    public MailInspectionJobPublicId {
        Objects.requireNonNull(value, "value must not be null");
        if (internalId <= 0) {
            throw new IllegalArgumentException("internalId must be positive");
        }
    }
}
