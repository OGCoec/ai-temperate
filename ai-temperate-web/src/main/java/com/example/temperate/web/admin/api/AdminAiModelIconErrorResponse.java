package com.example.temperate.web.admin.api;

import java.time.Instant;

/**
 * 是管理员模型图标接口专用的错误响应，既提供稳定业务错误，也提供受管理员认证边界保护的原始异常诊断信息。
 *
 * <p>该结构不替代全局错误响应，避免开发阶段诊断字段扩散到其他公开接口。
 */
public record AdminAiModelIconErrorResponse(
        String code,
        String message,
        String exceptionType,
        String exceptionMessage,
        String rootCauseType,
        String rootCauseMessage,
        Instant timestamp) {
}
