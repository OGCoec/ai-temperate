package com.example.temperate.service.user.apichat.billing;

import com.example.temperate.service.user.aiinference.api.ApiInferenceReservationRecoveryService;

/**
 * 该服务是来批量扫描超过最大流时长加安全缓冲仍为 RESERVED 的外部 API 用量，并执行全额崩溃退款。
 */
public interface ApiChatReservationRecoveryService
        extends ApiInferenceReservationRecoveryService {
}
