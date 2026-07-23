package com.example.temperate.service.auth.session.refresh.dto.result;

/**
 * 表示刷新会话绑定校验与滑动续期后的结果。
 *
 * <p>只有状态为有效时才携带会话快照，调用方必须按失败状态执行清理或拒绝逻辑。</p>
 */
public record RefreshSessionValidation(Status status, RefreshSessionSnapshot session) {

    public enum Status {
        VALID,
        MISSING_OR_EXPIRED,
        DEVICE_MISMATCH,
        CSRF_MISMATCH,
        INDEX_MISSING
    }
}
