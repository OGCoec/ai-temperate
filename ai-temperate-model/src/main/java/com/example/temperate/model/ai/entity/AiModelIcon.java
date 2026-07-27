package com.example.temperate.model.ai.entity;

import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 表示可被多个 AI 模型复用的图标资源持久化实体。
 *
 * <p>该实体同时承载最终公开 URL 和可选 OSS Object Key；Object Key 为空表示图片由外部站点托管，
 * 不负责公共 ID 编码、图片内容验证或 OSS 操作。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class AiModelIcon {

    private Long id;
    private String iconName;
    private String iconUrl;
    private String objectKey;
    private String description;
    private LocalDate createdAt;
    private LocalDate updatedAt;
}
