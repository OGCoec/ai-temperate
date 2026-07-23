package com.example.temperate.service.registration.dto.result;

import com.example.temperate.service.registration.enums.VerificationChannel;
import java.time.Instant;

/**
 * 返回注册验证码投递请求的渠道、冷却期与流程状态信息。
 */
public record VerificationDispatchResult(
        VerificationChannel channel,
        Instant acceptedAt) {
}
