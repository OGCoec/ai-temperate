package com.example.temperate.model.ai.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 该只读投影是来在预扣事务内一次锁定并重新验证 Key、账号、模型及授权映射，同时提供计费和上下文限制所需模型参数。
 */
@Getter
@Setter
@NoArgsConstructor
public class ApiKeyReservationAuthorization {

    private byte[] apiKeyId;
    private Long loginIdentityId;
    private byte[] keyDigest;
    private Integer keyStatus;
    private OffsetDateTime expiresAt;
    private Integer accountStatus;
    private Long aiModelId;
    private String modelName;
    private String vendor;
    private Boolean modelEnabled;
    private Integer mappingStatus;
    private BigDecimal inputRatio;
    private BigDecimal cachedInputRatio;
    private BigDecimal outputRatio;
    private Long contextWindowTokens;
    private Long maxOutputTokens;

    public byte[] getApiKeyId() {
        return apiKeyId == null ? null : apiKeyId.clone();
    }

    public void setApiKeyId(byte[] apiKeyId) {
        this.apiKeyId = apiKeyId == null ? null : apiKeyId.clone();
    }

    public byte[] getKeyDigest() {
        return keyDigest == null ? null : keyDigest.clone();
    }

    public void setKeyDigest(byte[] keyDigest) {
        this.keyDigest = keyDigest == null ? null : keyDigest.clone();
    }
}
