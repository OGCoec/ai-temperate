package com.example.temperate.service.registration.verification.service.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodeCommand;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.enums.RegistrationStatus;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.flow.domain.RegistrationFlowSnapshot;
import com.example.temperate.service.registration.flow.security.ProtectedRegistrationAccess;
import com.example.temperate.service.registration.flow.security.RegistrationTokenProtector;
import com.example.temperate.service.registration.flow.store.RegistrationFlowStore;
import com.example.temperate.service.registration.verification.service.SixDigitVerificationCodeVerifier;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 通过注册流程 Redis 状态机校验并消费六位数验证码。
 *
 * <p>实现先对输入做固定格式校验，再用注册令牌和渠道计算 HMAC 摘要，最终把摘要交给现有 Redis Lua
 * 原子完成比较、失败次数递增、成功消费和状态更新。该实现不记录明文验证码或目标地址。</p>
 */
@Service
public final class RedisSixDigitVerificationCodeVerifierImpl
        implements SixDigitVerificationCodeVerifier {

    private final RegistrationFlowStore flowStore;
    private final RegistrationTokenProtector tokenProtector;
    private final Clock clock;

    public RedisSixDigitVerificationCodeVerifierImpl(
            RegistrationFlowStore flowStore,
            RegistrationTokenProtector tokenProtector,
            Clock clock) {
        this.flowStore = Objects.requireNonNull(flowStore, "flowStore must not be null");
        this.tokenProtector =
                Objects.requireNonNull(tokenProtector, "tokenProtector must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public RegistrationStatusResult verify(RegistrationVerifyCodeCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.code() == null || !command.code().matches("^[0-9]{6}$")) {
            throw new RegistrationException(
                    RegistrationErrorCode.VERIFICATION_CODE_INVALID,
                    "Verification code is invalid.");
        }
        Objects.requireNonNull(command.channel(), "channel must not be null");
        ProtectedRegistrationAccess access = tokenProtector.protect(command.access());
        HmacIdentifier digest = tokenProtector.codeDigest(
                command.access().registerToken(), command.channel(), command.code());
        // 摘要比较、失败计数和成功消费必须在同一 Redis Lua 中完成，避免并发请求重复使用同一验证码。
        RegistrationFlowSnapshot snapshot =
                flowStore.verifyCode(access, command.channel(), digest, clock.instant());
        return toStatus(snapshot);
    }

    private static RegistrationStatusResult toStatus(RegistrationFlowSnapshot snapshot) {
        RegistrationStatus status = snapshot.completing()
                ? RegistrationStatus.COMPLETING
                : snapshot.readyToComplete()
                        ? RegistrationStatus.READY_TO_COMPLETE
                        : RegistrationStatus.ACTIVE;
        return new RegistrationStatusResult(
                status,
                snapshot.humanVerified(),
                snapshot.emailVerified(),
                snapshot.phoneVerified(),
                snapshot.createdAt(),
                snapshot.expiresAt(),
                snapshot.absoluteExpiresAt(),
                snapshot.email(),
                snapshot.phone());
    }
}
