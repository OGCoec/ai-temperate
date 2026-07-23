package com.example.temperate.service.registration.flow.store.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.flow.domain.RegistrationActor;
import com.example.temperate.service.registration.flow.domain.RegistrationCompletionClaim;
import com.example.temperate.service.registration.flow.domain.RegistrationFlow;
import com.example.temperate.service.registration.flow.domain.RegistrationFlowSnapshot;
import com.example.temperate.service.registration.flow.security.ProtectedRegistrationAccess;
import com.example.temperate.service.registration.flow.store.RegistrationFlowStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 基于 Redis Lua 脚本实现的注册流程状态机存储。
 *
 * <p>用途：保存注册流程、验证码、挑战及风险控制状态，并提供流程创建、校验、领取完成权和清理操作。</p>
 *
 * <p>并发安全原理：每个会同时读取并改变流程状态的操作都由单段 Lua 脚本完成，使访问材料校验、过期判断、
 * 尝试次数更新和状态转换在 Redis 内原子执行，避免多实例并发下的重复验证码消费或重复完成。</p>
 */
@Service
public final class RedisRegistrationFlowStore implements RegistrationFlowStore {

    private static final Duration REQUIRED_FLOW_IDLE_TTL = Duration.ofMinutes(10);
    private static final Duration REQUIRED_FLOW_ABSOLUTE_TTL = Duration.ofMinutes(30);
    private static final Duration REQUIRED_CONFLICT_WINDOW = Duration.ofMinutes(5);
    private static final Duration REQUIRED_CONFLICT_BLOCK = Duration.ofHours(2);
    private static final Duration REQUIRED_CODE_TTL = Duration.ofMinutes(5);
    private static final Duration REQUIRED_SEND_COOLDOWN = Duration.ofSeconds(60);
    private static final int REQUIRED_MAX_SENDS_PER_CHANNEL = 5;
    private static final int REQUIRED_MAX_CODE_ATTEMPTS = 5;
    private static final int REQUIRED_MAX_TOTAL_VERIFY_FAILURES = 10;

    // Lua 将同一流程的校验和状态变更合并为一次 Redis 原子执行，而不是由多个往返请求拼接。
    private static final RedisScript<Long> CREATE_SCRIPT =
            longScript("create_registration_flow.lua");
    private static final RedisScript<List> GET_SCRIPT =
            listScript("get_registration_flow.lua");
    private static final RedisScript<Long> RECORD_CONFLICT_SCRIPT =
            longScript("record_registration_conflict.lua");
    private static final RedisScript<Long> ISSUE_CODE_SCRIPT =
            longScript("issue_registration_code.lua");
    private static final RedisScript<Long> CLAIM_CODE_DELIVERY_ATTEMPT_SCRIPT =
            longScript("claim_registration_code_delivery_attempt.lua");
    private static final RedisScript<Long> RELEASE_CODE_DELIVERY_FOR_RETRY_SCRIPT =
            longScript("release_registration_code_delivery_for_retry.lua");
    private static final RedisScript<Long> MARK_CODE_DELIVERY_SUCCESS_SCRIPT =
            longScript("mark_registration_code_delivery_success.lua");
    private static final RedisScript<Long> MARK_CODE_DELIVERY_ACCEPTED_SCRIPT =
            longScript("mark_registration_code_delivery_accepted.lua");
    private static final RedisScript<Long> MARK_CODE_DELIVERY_UNKNOWN_SCRIPT =
            longScript("mark_registration_code_delivery_unknown.lua");
    private static final RedisScript<String> FIND_CODE_DELIVERY_UNKNOWN_SCRIPT =
            stringScript("find_registration_code_delivery_unknown.lua");
    private static final RedisScript<Long> COMPENSATE_CODE_DELIVERY_FAILURE_SCRIPT =
            longScript("compensate_registration_code_delivery_failure.lua");
    private static final RedisScript<List> VERIFY_CODE_SCRIPT =
            listScript("verify_registration_code.lua");
    private static final RedisScript<List> VERIFY_CODES_SCRIPT =
            listScript("verify_registration_codes.lua");
    private static final RedisScript<List> MARK_HUMAN_SCRIPT =
            listScript("mark_registration_human_verified.lua");
    private static final RedisScript<List> CLAIM_COMPLETION_SCRIPT =
            listScript("claim_registration_completion.lua");
    private static final RedisScript<Long> RELEASE_COMPLETION_SCRIPT =
            longScript("release_registration_completion.lua");
    private static final RedisScript<Long> DELETE_SCRIPT =
            longScript("delete_registration_flow.lua");
    private static final Map<VerificationChannel, ChannelBinding> CHANNEL_BINDINGS =
            channelBindings();

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory redisKeyFactory;
    private final long conflictWindowMillis;
    private final long conflictBlockSeconds;
    private final long codeTtlMillis;
    private final long sendCooldownMillis;
    private final int maxSendsPerChannel;
    private final int maxCodeAttempts;

    public RedisRegistrationFlowStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory redisKeyFactory,
            @Value("${app.registration.conflict-window:5m}") Duration conflictWindow,
            @Value("${app.registration.conflict-block-duration:2h}")
                    Duration conflictBlockDuration,
            @Value("${app.registration.code-ttl:5m}") Duration codeTtl,
            @Value("${app.registration.send-cooldown:60s}") Duration sendCooldown,
            @Value("${app.registration.max-sends-per-channel:5}") int maxSendsPerChannel,
            @Value("${app.registration.max-code-attempts:5}") int maxCodeAttempts) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.redisKeyFactory =
                Objects.requireNonNull(redisKeyFactory, "redisKeyFactory must not be null");
        this.conflictWindowMillis = requireExactDuration(
                "conflict window", conflictWindow, REQUIRED_CONFLICT_WINDOW).toMillis();
        this.conflictBlockSeconds = requireExactDuration(
                "conflict block duration", conflictBlockDuration, REQUIRED_CONFLICT_BLOCK)
                .toSeconds();
        this.codeTtlMillis =
                requireExactDuration("code TTL", codeTtl, REQUIRED_CODE_TTL).toMillis();
        this.sendCooldownMillis = requireExactDuration(
                "send cooldown", sendCooldown, REQUIRED_SEND_COOLDOWN).toMillis();
        if (maxSendsPerChannel != REQUIRED_MAX_SENDS_PER_CHANNEL) {
            throw new IllegalArgumentException(
                    "Registration max sends per channel must be exactly 5.");
        }
        if (maxCodeAttempts != REQUIRED_MAX_CODE_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "Registration max code attempts must be exactly 5.");
        }
        this.maxSendsPerChannel = maxSendsPerChannel;
        this.maxCodeAttempts = maxCodeAttempts;
    }

    @Override
    public boolean isBlocked(RegistrationActor actor) {
        RegistrationActor validActor = requireActor(actor);
        String blockKey = redisKeyFactory.registrationBlockKey(validActor.actorId());
        String globalBlockKey = redisKeyFactory.globalDeviceBlockKey(
                validActor.globalDeviceHash());
        try {
            Boolean blocked = redisTemplate.hasKey(blockKey);
            Boolean globalBlocked = redisTemplate.hasKey(globalBlockKey);
            if (blocked == null || globalBlocked == null) {
                throw unavailable("Redis did not return a block lookup result.");
            }
            return blocked || globalBlocked;
        } catch (RegistrationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Registration block lookup failed.", exception);
        }
    }

    @Override
    public boolean recordConflict(RegistrationActor actor, Instant occurredAt) {
        return recordConflict(actor, true, true, occurredAt);
    }

    @Override
    public boolean recordConflict(
            RegistrationActor actor,
            boolean phoneConflict,
            boolean emailConflict,
            Instant occurredAt) {
        RegistrationActor validActor = requireActor(actor);
        Instant validOccurredAt = requireInstant(occurredAt);
        Long status = executeLong(
                RECORD_CONFLICT_SCRIPT,
                List.of(
                        redisKeyFactory.registrationConflictKey(validActor.actorId()),
                        redisKeyFactory.registrationBlockKey(validActor.actorId()),
                        redisKeyFactory.globalDeviceBlockKey(validActor.globalDeviceHash())),
                Long.toString(validOccurredAt.toEpochMilli()),
                Long.toString(conflictWindowMillis),
                Long.toString(conflictBlockSeconds),
                phoneConflict ? "1" : "0",
                emailConflict ? "1" : "0");
        return switch (status.intValue()) {
            case 0 -> false;
            case 1 -> true;
            default -> throw unavailable("Unexpected registration conflict result.");
        };
    }

    @Override
    public void create(RegistrationFlow flow) {
        RegistrationFlow validFlow = requireFlow(flow);
        ProtectedRegistrationAccess access = validFlow.access();
        Long status = executeLong(
                CREATE_SCRIPT,
                List.of(
                        redisKeyFactory.registrationFlowKey(access.flowId()),
                        redisKeyFactory.registrationChallengeKey(access.challengeId())),
                Integer.toString(validFlow.schemaVersion()),
                validFlow.email(),
                validFlow.phone(),
                access.deviceHash().value(),
                access.ipHash().value(),
                access.flowCsrfHash().value(),
                access.challengeId().value(),
                Long.toString(validFlow.createdAt().toEpochMilli()),
                Long.toString(validFlow.expiresAt().toEpochMilli()),
                Long.toString(validFlow.absoluteExpiresAt().toEpochMilli()),
                "0",
                "0",
                "0",
                access.flowId().value(),
                Long.toString(REQUIRED_FLOW_IDLE_TTL.toMillis()));
        switch (status.intValue()) {
            case 0 -> {
                return;
            }
            case -1 -> throw invalidInput(
                    "Registration flow must use a 10 minute idle and 30 minute absolute TTL.");
            case 1 -> throw unavailable("Registration flow identifier collision.");
            default -> throw unavailable("Unexpected registration flow creation result.");
        }
    }

    @Override
    public RegistrationFlowSnapshot getRequired(
            ProtectedRegistrationAccess access, Instant now) {
        ProtectedRegistrationAccess validAccess = requireAccess(access);
        List<?> result = executeList(
                GET_SCRIPT,
                List.of(
                        redisKeyFactory.registrationFlowKey(validAccess.flowId()),
                        redisKeyFactory.registrationChallengeKey(validAccess.challengeId())),
                accessArguments(validAccess, requireInstant(now), true));
        return snapshotOrCommonError(result);
    }

    @Override
    public RegistrationFlowSnapshot markHumanVerified(
            ProtectedRegistrationAccess access, Instant now) {
        ProtectedRegistrationAccess validAccess = requireAccess(access);
        List<?> result = executeList(
                MARK_HUMAN_SCRIPT,
                List.of(
                        redisKeyFactory.registrationFlowKey(validAccess.flowId()),
                        redisKeyFactory.registrationChallengeKey(validAccess.challengeId())),
                validAccess.flowCsrfHash().value(),
                validAccess.deviceHash().value(),
                validAccess.ipHash().value(),
                validAccess.challengeId().value(),
                Long.toString(requireInstant(now).toEpochMilli()),
                validAccess.flowId().value());
        int status = resultStatus(result);
        if (status == 0) {
            return snapshot(result);
        }
        if (status == 4) {
            throw error(
                    RegistrationErrorCode.TURNSTILE_REJECTED,
                    "Turnstile challenge was already consumed or is invalid.");
        }
        throw commonFlowError(status);
    }

    @Override
    public void issueCode(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier codeDigest,
            HmacIdentifier sendOperationId,
            Instant now) {
        ProtectedRegistrationAccess validAccess = requireAccess(access);
        ChannelBinding binding = requireChannel(channel);
        HmacIdentifier validDigest = requireCodeDigest(codeDigest);
        HmacIdentifier validOperationId = requireOperationId(sendOperationId);
        // 先原子登记待投递操作、冷却时间和发送次数，再由投递协调器确认或补偿，避免并发重复发码。
        Long status = executeLong(
                ISSUE_CODE_SCRIPT,
                List.of(
                        redisKeyFactory.registrationFlowKey(validAccess.flowId()),
                        binding.codeKey(redisKeyFactory, validAccess),
                        redisKeyFactory.registrationSendRiskKey(validAccess.deviceHash()),
                        redisKeyFactory.registrationBlockKey(validAccess.deviceHash()),
                        redisKeyFactory.globalDeviceBlockKey(validAccess.globalDeviceHash())),
                validAccess.flowCsrfHash().value(),
                validAccess.deviceHash().value(),
                validAccess.ipHash().value(),
                validAccess.challengeId().value(),
                Long.toString(requireInstant(now).toEpochMilli()),
                validDigest.value(),
                validOperationId.value(),
                Long.toString(codeTtlMillis),
                Long.toString(sendCooldownMillis),
                Integer.toString(maxSendsPerChannel),
                binding.actualSendCountField(),
                binding.lastIssuedAtField(),
                binding.cooldownViolationCountField(),
                Long.toString(conflictWindowMillis),
                Long.toString(conflictBlockSeconds));
        switch (status.intValue()) {
            case 0 -> {
                return;
            }
            case 1, 2, 3 -> throw commonFlowError(status.intValue());
            case 4 -> throw error(
                    RegistrationErrorCode.HUMAN_VERIFICATION_REQUIRED,
                    "Human verification is required before sending a code.");
            case 5 -> throw error(
                    RegistrationErrorCode.VERIFICATION_COOLDOWN,
                    "Verification code send cooldown is active.");
            case 6 -> throw error(
                    RegistrationErrorCode.VERIFICATION_SEND_LIMIT,
                    "Verification code send limit was reached.");
            default -> throw unavailable("Unexpected verification code issue result.");
        }
    }

    @Override
    public boolean claimCodeDeliveryAttempt(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier sendOperationId,
            String messageId,
            int attemptNo) {
        ProtectedRegistrationAccess validAccess = requireAccess(access);
        ChannelBinding binding = requireChannel(channel);
        HmacIdentifier validOperationId = requireOperationId(sendOperationId);
        Long status = executeLong(
                CLAIM_CODE_DELIVERY_ATTEMPT_SCRIPT,
                List.of(
                        redisKeyFactory.registrationFlowKey(validAccess.flowId()),
                        binding.codeKey(redisKeyFactory, validAccess)),
                validAccess.flowCsrfHash().value(),
                validAccess.deviceHash().value(),
                validAccess.ipHash().value(),
                validAccess.challengeId().value(),
                validOperationId.value(),
                Integer.toString(attemptNo),
                requireMessageId(messageId));
        return switch (status.intValue()) {
            case 0 -> false;
            case 1 -> true;
            case 3 -> throw commonFlowError(3);
            default -> throw unavailable("Unexpected code delivery claim result.");
        };
    }

    @Override
    public boolean releaseCodeDeliveryForRetry(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier sendOperationId,
            String messageId) {
        ProtectedRegistrationAccess validAccess = requireAccess(access);
        ChannelBinding binding = requireChannel(channel);
        HmacIdentifier validOperationId = requireOperationId(sendOperationId);
        Long status = executeLong(
                RELEASE_CODE_DELIVERY_FOR_RETRY_SCRIPT,
                List.of(
                        redisKeyFactory.registrationFlowKey(validAccess.flowId()),
                        binding.codeKey(redisKeyFactory, validAccess)),
                validAccess.flowCsrfHash().value(),
                validAccess.deviceHash().value(),
                validAccess.ipHash().value(),
                validAccess.challengeId().value(),
                validOperationId.value(),
                requireMessageId(messageId));
        return switch (status.intValue()) {
            case 0 -> false;
            case 1 -> true;
            case 3 -> throw commonFlowError(3);
            default -> throw unavailable("Unexpected code delivery release result.");
        };
    }

    @Override
    public boolean markCodeDeliverySucceeded(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier sendOperationId) {
        ProtectedRegistrationAccess validAccess = requireAccess(access);
        ChannelBinding binding = requireChannel(channel);
        HmacIdentifier validOperationId = requireOperationId(sendOperationId);
        Long status = executeLong(
                MARK_CODE_DELIVERY_SUCCESS_SCRIPT,
                List.of(
                        redisKeyFactory.registrationFlowKey(validAccess.flowId()),
                        binding.codeKey(redisKeyFactory, validAccess),
                        redisKeyFactory.registrationSendRiskKey(validAccess.deviceHash()),
                        redisKeyFactory.registrationBlockKey(validAccess.deviceHash()),
                        redisKeyFactory.globalDeviceBlockKey(validAccess.globalDeviceHash())),
                validAccess.flowCsrfHash().value(),
                validAccess.deviceHash().value(),
                validAccess.ipHash().value(),
                validAccess.challengeId().value(),
                validOperationId.value(),
                binding.actualSendCountField(),
                Long.toString(conflictWindowMillis),
                Long.toString(conflictBlockSeconds));
        return switch (status.intValue()) {
            case 0 -> false;
            case 1, 2 -> true;
            case 3 -> throw commonFlowError(3);
            default -> throw unavailable("Unexpected code delivery success result.");
        };
    }

    @Override
    public boolean markCodeDeliveryAccepted(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier sendOperationId,
            String providerMessageId,
            String providerStatus) {
        ProtectedRegistrationAccess validAccess = requireAccess(access);
        ChannelBinding binding = requireChannel(channel);
        HmacIdentifier validOperationId = requireOperationId(sendOperationId);
        Long status = executeLong(
                MARK_CODE_DELIVERY_ACCEPTED_SCRIPT,
                List.of(
                        redisKeyFactory.registrationFlowKey(validAccess.flowId()),
                        binding.codeKey(redisKeyFactory, validAccess),
                        redisKeyFactory.registrationSendRiskKey(validAccess.deviceHash()),
                        redisKeyFactory.registrationBlockKey(validAccess.deviceHash()),
                        redisKeyFactory.globalDeviceBlockKey(validAccess.globalDeviceHash())),
                validAccess.flowCsrfHash().value(),
                validAccess.deviceHash().value(),
                validAccess.ipHash().value(),
                validAccess.challengeId().value(),
                validOperationId.value(),
                binding.actualSendCountField(),
                Long.toString(conflictWindowMillis),
                Long.toString(conflictBlockSeconds),
                requireText(providerMessageId),
                requireText(providerStatus));
        return switch (status.intValue()) {
            case 0 -> false;
            case 1, 2 -> true;
            case 3 -> throw commonFlowError(3);
            default -> throw unavailable("Unexpected code delivery acceptance result.");
        };
    }

    @Override
    public boolean markCodeDeliveryOutcomeUnknown(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier sendOperationId,
            String safeReason) {
        ProtectedRegistrationAccess validAccess = requireAccess(access);
        ChannelBinding binding = requireChannel(channel);
        HmacIdentifier validOperationId = requireOperationId(sendOperationId);
        Long status = executeLong(
                MARK_CODE_DELIVERY_UNKNOWN_SCRIPT,
                List.of(
                        redisKeyFactory.registrationFlowKey(validAccess.flowId()),
                        binding.codeKey(redisKeyFactory, validAccess)),
                validAccess.flowCsrfHash().value(),
                validAccess.deviceHash().value(),
                validAccess.ipHash().value(),
                validAccess.challengeId().value(),
                validOperationId.value(),
                requireText(safeReason));
        return switch (status.intValue()) {
            case 0 -> false;
            case 1 -> true;
            case 3 -> throw commonFlowError(3);
            default -> throw unavailable("Unexpected unknown delivery result.");
        };
    }

    @Override
    public String findCodeDeliveryOutcomeUnknown(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier sendOperationId) {
        ProtectedRegistrationAccess validAccess = requireAccess(access);
        ChannelBinding binding = requireChannel(channel);
        String reason = execute(
                FIND_CODE_DELIVERY_UNKNOWN_SCRIPT,
                List.of(
                        redisKeyFactory.registrationFlowKey(validAccess.flowId()),
                        binding.codeKey(redisKeyFactory, validAccess)),
                validAccess.flowCsrfHash().value(),
                validAccess.deviceHash().value(),
                validAccess.ipHash().value(),
                validAccess.challengeId().value(),
                requireOperationId(sendOperationId).value());
        return reason == null || reason.isBlank() ? null : requireText(reason);
    }

    @Override
    public boolean finalizeCodeDeliveryFailure(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier sendOperationId) {
        return compensateCodeDeliveryFailure(access, channel, sendOperationId);
    }

    @Override
    public boolean compensateCodeDeliveryFailure(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier sendOperationId) {
        ProtectedRegistrationAccess validAccess = requireAccess(access);
        ChannelBinding binding = requireChannel(channel);
        HmacIdentifier validOperationId = requireOperationId(sendOperationId);
        Long status = executeLong(
                COMPENSATE_CODE_DELIVERY_FAILURE_SCRIPT,
                List.of(
                        redisKeyFactory.registrationFlowKey(validAccess.flowId()),
                        binding.codeKey(redisKeyFactory, validAccess),
                        redisKeyFactory.registrationSendRiskKey(validAccess.deviceHash())),
                validAccess.flowCsrfHash().value(),
                validAccess.deviceHash().value(),
                validAccess.ipHash().value(),
                validAccess.challengeId().value(),
                validOperationId.value(),
                binding.verifiedField(),
                binding.actualSendCountField(),
                binding.lastIssuedAtField());
        return switch (status.intValue()) {
            case 0 -> false;
            case 1 -> true;
            case 3 -> throw commonFlowError(3);
            default -> throw unavailable("Unexpected code delivery compensation result.");
        };
    }

    @Override
    public RegistrationFlowSnapshot verifyCode(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier codeDigest,
            Instant now) {
        ProtectedRegistrationAccess validAccess = requireAccess(access);
        ChannelBinding binding = requireChannel(channel);
        HmacIdentifier validDigest = requireCodeDigest(codeDigest);
        // 校验摘要、递增失败次数和标记已验证必须在同一 Lua 执行中完成，避免验证码被并发重复使用。
        List<?> result = executeList(
                VERIFY_CODE_SCRIPT,
                List.of(
                        redisKeyFactory.registrationFlowKey(validAccess.flowId()),
                        binding.codeKey(redisKeyFactory, validAccess)),
                validAccess.flowCsrfHash().value(),
                validAccess.deviceHash().value(),
                validAccess.ipHash().value(),
                validAccess.challengeId().value(),
                Long.toString(requireInstant(now).toEpochMilli()),
                validDigest.value(),
                Integer.toString(maxCodeAttempts),
                binding.verifiedField());
        int status = resultStatus(result);
        if (status == 0) {
            return snapshot(result);
        }
        switch (status) {
            case 1, 2, 3 -> throw commonFlowError(status);
            case 4 -> throw error(
                    RegistrationErrorCode.HUMAN_VERIFICATION_REQUIRED,
                    "Human verification is required before verifying a code.");
            case 5 -> throw error(
                    RegistrationErrorCode.VERIFICATION_CODE_EXPIRED,
                    "Verification code is missing or expired.");
            case 6 -> throw error(
                    RegistrationErrorCode.VERIFICATION_CODE_INVALID,
                    "Verification code is invalid.");
            case 7 -> throw error(
                    RegistrationErrorCode.VERIFICATION_CODE_ATTEMPTS_EXHAUSTED,
                    "Verification code attempts were exhausted.");
            default -> throw unavailable("Unexpected verification code result.");
        }
    }

    @Override
    public RegistrationFlowSnapshot verifyCodes(
            ProtectedRegistrationAccess access,
            HmacIdentifier emailCodeDigest,
            HmacIdentifier phoneCodeDigest,
            Instant now) {
        ProtectedRegistrationAccess validAccess = requireAccess(access);
        HmacIdentifier validEmailDigest = requireCodeDigest(emailCodeDigest);
        HmacIdentifier validPhoneDigest = requireCodeDigest(phoneCodeDigest);
        // 双通道验证码在同一个状态转换中处理，防止只成功消费其中一项后留下部分完成状态。
        List<?> result = executeList(
                VERIFY_CODES_SCRIPT,
                List.of(
                        redisKeyFactory.registrationFlowKey(validAccess.flowId()),
                        redisKeyFactory.registrationEmailCodeKey(validAccess.emailCodeId()),
                        redisKeyFactory.registrationPhoneCodeKey(validAccess.phoneCodeId()),
                        redisKeyFactory.registrationVerifyRiskKey(validAccess.deviceHash()),
                        redisKeyFactory.registrationBlockKey(validAccess.deviceHash()),
                        redisKeyFactory.globalDeviceBlockKey(validAccess.globalDeviceHash())),
                validAccess.flowCsrfHash().value(),
                validAccess.deviceHash().value(),
                validAccess.ipHash().value(),
                validAccess.challengeId().value(),
                Long.toString(requireInstant(now).toEpochMilli()),
                validEmailDigest.value(),
                validPhoneDigest.value(),
                Integer.toString(maxCodeAttempts),
                Integer.toString(REQUIRED_MAX_TOTAL_VERIFY_FAILURES),
                Long.toString(conflictWindowMillis),
                Long.toString(conflictBlockSeconds));
        int status = resultStatus(result);
        if (status == 0) {
            return snapshot(result);
        }
        switch (status) {
            case 1, 2, 3 -> throw commonFlowError(status);
            case 4 -> throw error(
                    RegistrationErrorCode.HUMAN_VERIFICATION_REQUIRED,
                    "Human verification is required before verifying codes.");
            case 5 -> throw error(
                    RegistrationErrorCode.VERIFICATION_CODE_EXPIRED,
                    "A verification code is missing or expired.");
            case 6 -> throw error(
                    RegistrationErrorCode.VERIFICATION_CODE_INVALID,
                    "The verification codes were not both valid.");
            case 7 -> throw error(
                    RegistrationErrorCode.VERIFICATION_CODE_ATTEMPTS_EXHAUSTED,
                    "A verification code was invalidated after five failures.");
            case 8 -> throw unavailable("Registration verification is temporarily blocked.");
            default -> throw unavailable("Unexpected combined verification result.");
        }
    }

    @Override
    public RegistrationCompletionClaim claimCompletion(
            ProtectedRegistrationAccess access,
            HmacIdentifier claimId,
            Instant now) {
        ProtectedRegistrationAccess validAccess = requireAccess(access);
        HmacIdentifier validClaimId = requireClaimId(claimId);
        // 领取、前置状态检查和“正在完成”标记原子完成，作为跨应用实例的完成互斥与幂等边界。
        List<?> result = executeList(
                CLAIM_COMPLETION_SCRIPT,
                List.of(redisKeyFactory.registrationFlowKey(validAccess.flowId())),
                validAccess.flowCsrfHash().value(),
                validAccess.deviceHash().value(),
                validAccess.ipHash().value(),
                validAccess.challengeId().value(),
                Long.toString(requireInstant(now).toEpochMilli()),
                validClaimId.value());
        int status = resultStatus(result);
        if (status == 0) {
            return new RegistrationCompletionClaim(snapshot(result), validClaimId);
        }
        switch (status) {
            case 1, 2, 3 -> throw commonFlowError(status);
            case 4 -> throw error(
                    RegistrationErrorCode.HUMAN_VERIFICATION_REQUIRED,
                    "Human verification is required before completion.");
            case 5 -> throw error(
                    RegistrationErrorCode.EMAIL_VERIFICATION_REQUIRED,
                    "Email verification is required before completion.");
            case 6 -> throw error(
                    RegistrationErrorCode.PHONE_VERIFICATION_REQUIRED,
                    "Phone verification is required before completion.");
            case 7 -> throw error(
                    RegistrationErrorCode.REGISTRATION_ALREADY_COMPLETING,
                    "Registration completion is already claimed.");
            default -> throw unavailable("Unexpected registration completion claim result.");
        }
    }

    @Override
    public void releaseCompletionClaim(
            ProtectedRegistrationAccess access, HmacIdentifier claimId) {
        ProtectedRegistrationAccess validAccess = requireAccess(access);
        HmacIdentifier validClaimId = requireClaimId(claimId);
        Long status = executeLong(
                RELEASE_COMPLETION_SCRIPT,
                List.of(redisKeyFactory.registrationFlowKey(validAccess.flowId())),
                validAccess.flowCsrfHash().value(),
                validAccess.deviceHash().value(),
                validAccess.ipHash().value(),
                validAccess.challengeId().value(),
                validClaimId.value());
        switch (status.intValue()) {
            case 0, 1 -> {
                return;
            }
            case 3 -> throw commonFlowError(3);
            default -> throw unavailable("Unexpected registration completion release result.");
        }
    }

    @Override
    public void delete(ProtectedRegistrationAccess access) {
        ProtectedRegistrationAccess validAccess = requireAccess(access);
        Long status = executeLong(
                DELETE_SCRIPT,
                List.of(
                        redisKeyFactory.registrationFlowKey(validAccess.flowId()),
                        redisKeyFactory.registrationEmailCodeKey(validAccess.emailCodeId()),
                        redisKeyFactory.registrationPhoneCodeKey(validAccess.phoneCodeId()),
                        redisKeyFactory.registrationChallengeKey(validAccess.challengeId()),
                        redisKeyFactory.registrationSendRiskKey(validAccess.deviceHash()),
                        redisKeyFactory.registrationVerifyRiskKey(validAccess.deviceHash())),
                validAccess.flowCsrfHash().value(),
                validAccess.deviceHash().value(),
                validAccess.ipHash().value(),
                validAccess.challengeId().value());
        switch (status.intValue()) {
            case 0, 1 -> {
                return;
            }
            case 3 -> throw commonFlowError(3);
            default -> throw unavailable("Unexpected registration flow deletion result.");
        }
    }

    private Long executeLong(RedisScript<Long> script, List<String> keys, Object... arguments) {
        return execute(script, keys, arguments);
    }

    @SuppressWarnings("rawtypes")
    private List<?> executeList(
            RedisScript<List> script, List<String> keys, Object... arguments) {
        return execute(script, keys, arguments);
    }

    private <T> T execute(RedisScript<T> script, List<String> keys, Object... arguments) {
        try {
            // 脚本或 Redis 异常统一映射为受控业务不可用，避免把内部状态和凭据细节暴露给调用方。
            T result = redisTemplate.execute(script, keys, arguments);
            if (result == null) {
                throw unavailable("Redis registration script returned no result.");
            }
            return result;
        } catch (RegistrationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis registration script execution failed.", exception);
        }
    }

    private static Object[] accessArguments(
            ProtectedRegistrationAccess access, Instant now, boolean renewIdleTtl) {
        return new Object[] {
            access.flowCsrfHash().value(),
            access.deviceHash().value(),
            access.ipHash().value(),
            access.challengeId().value(),
            Long.toString(now.toEpochMilli()),
            renewIdleTtl ? Long.toString(REQUIRED_FLOW_IDLE_TTL.toMillis()) : "0"
        };
    }

    private static RegistrationFlowSnapshot snapshotOrCommonError(List<?> result) {
        int status = resultStatus(result);
        if (status != 0) {
            throw commonFlowError(status);
        }
        return snapshot(result);
    }

    private static RegistrationFlowSnapshot snapshot(List<?> result) {
        if (result.size() < 10) {
            throw unavailable("Redis registration snapshot is incomplete.");
        }
        try {
            return new RegistrationFlowSnapshot(
                    text(result.get(1)),
                    text(result.get(2)),
                    bit(result.get(3)),
                    bit(result.get(4)),
                    bit(result.get(5)),
                    bit(result.get(6)),
                    Instant.ofEpochMilli(number(result.get(7))),
                    Instant.ofEpochMilli(number(result.get(8))),
                    Instant.ofEpochMilli(number(result.get(9))));
        } catch (RegistrationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis registration snapshot is malformed.", exception);
        }
    }

    private static int resultStatus(List<?> result) {
        if (result.isEmpty()) {
            throw unavailable("Redis registration script returned an empty result.");
        }
        try {
            return Math.toIntExact(number(result.getFirst()));
        } catch (RuntimeException exception) {
            throw unavailable("Redis registration script returned an invalid status.", exception);
        }
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(text(value));
    }

    private static boolean bit(Object value) {
        return switch (text(value)) {
            case "0" -> false;
            case "1" -> true;
            default -> throw unavailable("Redis registration boolean field is malformed.");
        };
    }

    private static String text(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (value == null) {
            throw unavailable("Redis registration field is missing.");
        }
        return value.toString();
    }

    private static RegistrationFlow requireFlow(RegistrationFlow flow) {
        if (flow == null
                || flow.schemaVersion() != RegistrationFlow.CURRENT_SCHEMA_VERSION
                || flow.email() == null
                || flow.email().isBlank()
                || flow.phone() == null
                || flow.phone().isBlank()
                || flow.createdAt() == null
                || flow.expiresAt() == null
                || flow.absoluteExpiresAt() == null
                || !REQUIRED_FLOW_IDLE_TTL.equals(
                        Duration.between(flow.createdAt(), flow.expiresAt()))
                || !REQUIRED_FLOW_ABSOLUTE_TTL.equals(
                        Duration.between(flow.createdAt(), flow.absoluteExpiresAt()))) {
            throw invalidInput("Registration flow is invalid.");
        }
        requireAccess(flow.access());
        return flow;
    }

    private static ProtectedRegistrationAccess requireAccess(
            ProtectedRegistrationAccess access) {
        if (access == null
                || access.flowId() == null
                || access.flowCsrfHash() == null
                || access.challengeId() == null
                || access.deviceHash() == null
                || access.globalDeviceHash() == null
                || access.ipHash() == null
                || access.emailCodeId() == null
                || access.phoneCodeId() == null) {
            throw commonFlowError(3);
        }
        return access;
    }

    private static RegistrationActor requireActor(RegistrationActor actor) {
        if (actor == null || actor.actorId() == null || actor.globalDeviceHash() == null) {
            throw invalidInput("Registration actor is invalid.");
        }
        return actor;
    }

    private static HmacIdentifier requireCodeDigest(HmacIdentifier digest) {
        if (digest == null) {
            throw error(
                    RegistrationErrorCode.VERIFICATION_CODE_INVALID,
                    "Verification code digest is invalid.");
        }
        return digest;
    }

    private static HmacIdentifier requireClaimId(HmacIdentifier claimId) {
        if (claimId == null) {
            throw commonFlowError(3);
        }
        return claimId;
    }

    private static HmacIdentifier requireOperationId(HmacIdentifier operationId) {
        if (operationId == null) {
            throw commonFlowError(3);
        }
        return operationId;
    }

    private static String requireMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw invalidInput("Delivery message ID is required.");
        }
        return messageId;
    }

    /** 仅允许把低基数的诊断字段写入 Redis，避免第三方原文或敏感内容进入状态哈希。 */
    private static String requireText(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || !value.matches("^[A-Za-z0-9._:-]+$")) {
            throw invalidInput("Provider delivery diagnostic is invalid.");
        }
        return value;
    }

    private static ChannelBinding requireChannel(VerificationChannel channel) {
        ChannelBinding binding = CHANNEL_BINDINGS.get(channel);
        if (binding == null) {
            throw error(
                    RegistrationErrorCode.VERIFICATION_CHANNEL_UNSUPPORTED,
                    "Verification channel is unsupported.");
        }
        return binding;
    }

    private static Instant requireInstant(Instant instant) {
        if (instant == null) {
            throw invalidInput("Registration timestamp is required.");
        }
        return instant;
    }

    private static Duration requireExactDuration(
            String name, Duration actual, Duration required) {
        if (!required.equals(actual)) {
            throw new IllegalArgumentException(
                    "Registration " + name + " must be exactly " + required + ".");
        }
        return actual;
    }

    private static RegistrationException commonFlowError(int status) {
        return switch (status) {
            case 1 -> error(
                    RegistrationErrorCode.REGISTRATION_FLOW_NOT_FOUND,
                    "Registration flow was not found.");
            case 2 -> error(
                    RegistrationErrorCode.REGISTRATION_FLOW_EXPIRED,
                    "Registration flow has expired.");
            case 3 -> error(
                    RegistrationErrorCode.REGISTRATION_FLOW_FORBIDDEN,
                    "Registration flow credentials do not match.");
            default -> unavailable("Unexpected registration flow result.");
        };
    }

    private static RegistrationException invalidInput(String message) {
        return error(RegistrationErrorCode.INVALID_INPUT, message);
    }

    private static RegistrationException error(
            RegistrationErrorCode code, String message) {
        return new RegistrationException(code, message);
    }

    private static RegistrationException unavailable(String message) {
        return new RegistrationException(RegistrationErrorCode.AUTH_REGISTER_UNAVAILABLE, message);
    }

    private static RegistrationException unavailable(String message, Throwable cause) {
        return new RegistrationException(
                RegistrationErrorCode.AUTH_REGISTER_UNAVAILABLE, message, cause);
    }

    private static Map<VerificationChannel, ChannelBinding> channelBindings() {
        EnumMap<VerificationChannel, ChannelBinding> bindings =
                new EnumMap<>(VerificationChannel.class);
        bindings.put(
                VerificationChannel.EMAIL,
                new ChannelBinding(
                        RedisKeyFactory::registrationEmailCodeKey,
                        ProtectedRegistrationAccess::emailCodeId,
                        "emailActualSendCount",
                        "emailLastIssuedAt",
                        "emailCooldownViolationCount",
                        "emailVerified"));
        bindings.put(
                VerificationChannel.SMS,
                new ChannelBinding(
                        RedisKeyFactory::registrationPhoneCodeKey,
                        ProtectedRegistrationAccess::phoneCodeId,
                        "phoneActualSendCount",
                        "phoneLastIssuedAt",
                        "phoneCooldownViolationCount",
                        "phoneVerified"));
        return Map.copyOf(bindings);
    }

    private static RedisScript<Long> longScript(String fileName) {
        return script(fileName, Long.class);
    }

    private static RedisScript<String> stringScript(String fileName) {
        return script(fileName, String.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RedisScript<List> listScript(String fileName) {
        return (RedisScript) script(fileName, List.class);
    }

    private static <T> RedisScript<T> script(String fileName, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/registration/" + fileName));
        script.setResultType(resultType);
        return script;
    }

    private record ChannelBinding(
            BiFunction<RedisKeyFactory, HmacIdentifier, String> keyFactory,
            Function<ProtectedRegistrationAccess, HmacIdentifier> identifier,
            String actualSendCountField,
            String lastIssuedAtField,
            String cooldownViolationCountField,
            String verifiedField) {

        String codeKey(
                RedisKeyFactory redisKeyFactory, ProtectedRegistrationAccess access) {
            return keyFactory.apply(redisKeyFactory, identifier.apply(access));
        }
    }
}
