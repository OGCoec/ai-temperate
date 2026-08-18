package com.example.temperate.service.user.aiinference.api;

/**
 * 该服务是来批量收敛 Chat 与 Responses 因进程崩溃或终态执行器拒绝而遗留的陈旧 RESERVED 用量。
 */
public interface ApiInferenceReservationRecoveryService {

    int recoverExpiredReservations();
}
