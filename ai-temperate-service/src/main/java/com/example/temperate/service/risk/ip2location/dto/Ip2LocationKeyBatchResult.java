package com.example.temperate.service.risk.ip2location.dto;

/**
 * 汇总一次原子批量导入的新增、覆盖和重复数量，不包含任何凭据内容。
 */
public record Ip2LocationKeyBatchResult(
        int acceptedCount,
        int createdCount,
        int updatedCount,
        int duplicateCount) {
}
