package com.example.temperate.service.auth.totp.verification;

import java.time.Instant;

/**
 * 定义使用数据库当前生效 TOTP 密钥验证并领取时间片的敏感操作边界。
 */
public interface CurrentTotpVerificationService {

    void verifyAndClaim(long userId, String code, Instant now);
}
