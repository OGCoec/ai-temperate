package com.example.temperate.service.auth.session.refresh.store;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.session.refresh.dto.command.NewRefreshSession;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionRevocation;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionSnapshot;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionValidation;

/**
 * 定义刷新会话的创建、原子续期、CSRF 轮换和撤销存储契约。
 *
 * <p>实现必须同时维护会话本体和按用户索引，且不得把原始 RT、CSRF 或设备标识持久化为可重放值。</p>
 */
public interface RefreshSessionStore {

    RefreshSessionSnapshot create(NewRefreshSession session);

    RefreshSessionValidation validateAndRenew(
            HmacIdentifier refreshTokenHash,
            HmacIdentifier deviceHash,
            HmacIdentifier csrfHash);

    RefreshSessionValidation bootstrapAndRenew(
            HmacIdentifier refreshTokenHash,
            HmacIdentifier deviceHash,
            HmacIdentifier newCsrfHash);

    RefreshSessionRevocation revoke(
            HmacIdentifier refreshTokenHash,
            HmacIdentifier deviceHash,
            HmacIdentifier csrfHash);

    int revokeAllForUser(long userId);
}
