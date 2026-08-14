package com.example.temperate.model.ai.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 该实体是来保存外部 API 调用的一对一预扣详情、厂商快照与结算差额，不保存价格倍率或客户端幂等键。
 */
@Getter
@Setter
@NoArgsConstructor
public class AiModelApiUsageDetail {

    private Long id;
    private Long usageId;
    private String vendorSnapshot;
    private Boolean stream;
    private Long reservedQuotaMinor;
    private Long settlementDeltaMinor;
}
