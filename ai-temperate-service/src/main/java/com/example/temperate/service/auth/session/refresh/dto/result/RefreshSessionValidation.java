package com.example.temperate.service.auth.session.refresh.dto.result;

/**
 * 表示刷新会话绑定校验与滑动续期后的结果。
 *
 * <p>只有状态为有效时才携带会话快照，调用方必须按失败状态执行清理或拒绝逻辑。</p>
 */
public record RefreshSessionValidation(Status status, RefreshSessionSnapshot session) {

    public RefreshSessionValidation {
        if (status == null) {
            throw new IllegalArgumentException("Refresh session status is required.");
        }
        if ((status == Status.VALID) != (session != null)) {
            throw new IllegalArgumentException(
                    "Only a valid refresh session result may contain a snapshot.");
        }
    }

    public enum Status {
        VALID,
        MISSING_OR_EXPIRED,
        DEVICE_MISMATCH,
        CSRF_MISMATCH,
        INDEX_MISSING,
        PREAUTH_MISMATCH,
        TTL_INVARIANT_VIOLATION
    }
}
