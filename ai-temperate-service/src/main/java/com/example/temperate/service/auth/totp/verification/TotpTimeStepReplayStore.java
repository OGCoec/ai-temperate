package com.example.temperate.service.auth.totp.verification;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import java.time.Duration;

/**
 * 定义当前 TOTP 时间片在 Redis 中一次性领取的防重放边界。
 */
public interface TotpTimeStepReplayStore {

    boolean claim(HmacIdentifier replayId, Duration ttl);
}
