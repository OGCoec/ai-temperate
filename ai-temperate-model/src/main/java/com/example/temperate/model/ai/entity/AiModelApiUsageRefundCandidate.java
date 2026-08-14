package com.example.temperate.model.ai.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 该投影是来批量承载超过恢复截止时间的外部 API 预扣记录及其账号归属，供聚合退款避免逐条数据库 I/O。
 */
@Getter
@Setter
@NoArgsConstructor
public class AiModelApiUsageRefundCandidate {

    private Long usageId;
    private Long loginIdentityId;
    private Long reservedQuotaMinor;
}
