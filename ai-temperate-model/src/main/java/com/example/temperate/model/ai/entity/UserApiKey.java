package com.example.temperate.model.ai.entity;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 该实体是来承载用户外部 API Key 的创建幂等标识、不可恢复凭证摘要、生命周期状态和乐观锁版本，不保存完整密钥或可解密密文。
 */
@Getter
@Setter
@NoArgsConstructor
public class UserApiKey {

    private Long id;
    private Long loginIdentityId;
    private UUID createIdempotencyKey;
    private byte[] keyDigest;
    private String keyHint;
    private Integer status;
    private OffsetDateTime expiresAt;
    private OffsetDateTime lastUsedAt;
    private Long rowVersion;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;
}
