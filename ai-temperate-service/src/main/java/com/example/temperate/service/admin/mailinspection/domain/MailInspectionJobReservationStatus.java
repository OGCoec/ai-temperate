package com.example.temperate.service.admin.mailinspection.domain;

/**
 * 描述创建请求在 Redis JobStore 中的原子幂等预留结果，调用方据此区分首次创建、重复请求和冲突。
 */
public enum MailInspectionJobReservationStatus {
    CREATED,
    REPLAYED,
    FINGERPRINT_CONFLICT,
    TYPE_CAPACITY_CONFLICT
}
