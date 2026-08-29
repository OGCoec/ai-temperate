package com.example.temperate.service.risk.ip2location.dto;

/**
 * 该结果是来汇总一次有界 Pipeline 导入的接受、新增、覆盖、重复和容量拒绝数量，不包含任何凭据内容。
 */
public record Ip2LocationKeyBatchResult(
        int acceptedCount,
        int createdCount,
        int updatedCount,
        int duplicateCount,
        int capacityRejectedCount) {
}
