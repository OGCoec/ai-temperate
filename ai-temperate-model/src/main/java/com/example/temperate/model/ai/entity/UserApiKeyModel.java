package com.example.temperate.model.ai.entity;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 该实体是来承载 API Key 与模型之间可软撤销、可恢复的授权映射，并以联合主键保持授权身份稳定。
 */
@Getter
@Setter
@NoArgsConstructor
public class UserApiKeyModel {

    private byte[] userApiKeyId;
    private Long aiModelId;
    private Integer status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;
}
