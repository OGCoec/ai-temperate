package com.example.temperate.service.auth.session.refresh.dto.result;

/**
 * 表示撤销刷新会话后的存储层结果状态。
 */
public record RefreshSessionRevocation(Status status) {

    public enum Status {
        REVOKED,
        MISSING_OR_EXPIRED,
        DEVICE_MISMATCH,
        CSRF_MISMATCH,
        INDEX_BOUND_EXCEEDED
    }
}
