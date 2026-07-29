package com.example.temperate.service.admin.mailinspection.lease;

/**
 * 负责按固定节奏续签所有活动邮件检查任务的 Redis 滑动租约。
 *
 * <p>该接口只编排租约心跳，不负责读取任务或延长终态任务的保留时间。</p>
 */
public interface MailInspectionJobLeaseService {

    /**
     * 批量续签当前活动任务，Redis 不可用时让异常进入统一诊断链路。
     */
    void refreshActiveLeases();
}
