package com.example.temperate.service.admin.mailinspection.service;

import com.example.temperate.service.admin.mailinspection.domain.AdminMailInspectionCreateCommand;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobCreateResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobSnapshot;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 定义管理员创建、查询和恢复四类 Redis 邮箱检查任务的统一业务入口。
 */
public interface AdminMailInspectionJobService {

    Mono<MailInspectionJobCreateResult> create(
            MailInspectionType type,
            AdminMailInspectionCreateCommand command);

    MailInspectionJobSnapshot get(String jobId);

    List<MailInspectionJobSnapshot> getRecovered();

    Mono<MailInspectionJobSnapshot> resume(String jobId);
}
