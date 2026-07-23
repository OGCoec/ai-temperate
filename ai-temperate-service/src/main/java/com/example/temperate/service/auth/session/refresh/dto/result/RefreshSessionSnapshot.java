package com.example.temperate.service.auth.session.refresh.dto.result;

import java.time.Instant;

/**
 * 表示刷新会话在 Redis 中读取并校验后的状态快照。
 *
 * <p>快照中的联系方式只用于受控业务流程，不能替代数据库当前账号状态或直接返回给外部客户端。</p>
 */
public record RefreshSessionSnapshot(
        long userId,
        String publicId,
        String csrfHash,
        String email,
        String phone,
        String deviceHash,
        Instant expiresAt) {
}
