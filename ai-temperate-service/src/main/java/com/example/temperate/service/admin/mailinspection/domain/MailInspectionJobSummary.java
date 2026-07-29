package com.example.temperate.service.admin.mailinspection.domain;

import java.util.Map;

/**
 * 汇总任务当前各稳定结果码的数量，使用不可变 Map 支持四类任务共享查询协议。
 */
public record MailInspectionJobSummary(Map<MailInspectionResultStatus, Integer> counts) {

    public MailInspectionJobSummary {
        counts = counts == null ? Map.of() : Map.copyOf(counts);
    }
}
