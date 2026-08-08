package com.example.temperate.model.ai.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 保存视频模型用量的一对一计费快照，使视频专属字段与通用模型用量详情保持物理分离。
 *
 * <p>该实体只承载预扣时冻结的视频模式、媒体规模与官方成本 ticks，不保存视频内容、临时下载地址或授权信息。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class AiModelUsageVideoDetail {

    private byte[] usageId;
    private String videoMode;
    private String videoResolution;
    private Integer requestedDurationSeconds;
    private Integer inputImageCount;
    private Long inputVideoDurationMillis;
    private Long outputCostTicksPerSecond;
    private Long imageInputCostTicksEach;
    private Long videoInputCostTicksPerSecond;
    private Long estimatedProviderCostTicks;
}
