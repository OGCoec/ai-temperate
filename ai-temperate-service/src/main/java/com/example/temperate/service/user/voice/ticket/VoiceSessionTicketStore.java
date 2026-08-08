package com.example.temperate.service.user.voice.ticket;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import java.time.Duration;
import java.util.Optional;

/**
 * 定义语音票据在 Redis 中的限流创建和原子单次消费边界。
 */
public interface VoiceSessionTicketStore {

    void create(
            HmacIdentifier ticketHash,
            HmacIdentifier userRateHash,
            HmacIdentifier deviceRateHash,
            VoiceSessionTicketSnapshot snapshot,
            Duration ticketTtl,
            Duration rateWindow,
            int rateLimit);

    Optional<VoiceSessionTicketSnapshot> consume(HmacIdentifier ticketHash);
}
