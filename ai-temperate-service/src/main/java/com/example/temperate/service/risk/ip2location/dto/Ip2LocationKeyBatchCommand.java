package com.example.temperate.service.risk.ip2location.dto;

import com.example.temperate.service.risk.ip2location.domain.Ip2LocationImportMode;
import com.example.temperate.service.risk.ip2location.domain.Ip2LocationPlanType;
import java.util.List;

/**
 * 承载管理员已经完成 HTTP 边界校验的批量 IP2Location Key 导入命令，凭据有效期由服务端套餐规则计算。
 */
public record Ip2LocationKeyBatchCommand(
        Ip2LocationPlanType planType,
        long initialQuota,
        Ip2LocationImportMode mode,
        List<String> apiKeys) {

    @Override
    public String toString() {
        return "Ip2LocationKeyBatchCommand[redacted]";
    }
}
