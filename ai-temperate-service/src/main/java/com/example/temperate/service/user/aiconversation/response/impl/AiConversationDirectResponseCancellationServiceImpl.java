package com.example.temperate.service.user.aiconversation.response.impl;

import com.example.temperate.mapper.ai.AiModelUsageDetailMapper;
import com.example.temperate.mapper.ai.AiModelUsageMapper;
import com.example.temperate.model.ai.entity.AiModelUsage;
import com.example.temperate.model.ai.entity.AiModelUsageDetail;
import com.example.temperate.model.ai.enums.AiModelBillingStatus;
import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.context.AiConversationInterruptionSource;
import com.example.temperate.service.user.aiconversation.response.AiConversationDirectResponseActiveRegistry;
import com.example.temperate.service.user.aiconversation.response.AiConversationDirectResponseCancellationService;
import com.example.temperate.service.user.aiconversation.response.AiConversationDirectResponseCancellationStatus;
import com.example.temperate.service.user.aiconversation.response.AiConversationDirectResponseControlStore;
import com.example.temperate.service.user.aiconversation.response.rabbit.AiConversationDirectResponseControlPublisher;
import com.example.temperate.service.user.aiconversation.security.AiConversationIdempotencyHasher;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 实现直接 SSE Stop 的幂等协调：先写短期 Redis 意图，再取消本机或通知 Owner 实例，最终结算仍由流终态唯一拥有者执行。
 */
@Service
public final class AiConversationDirectResponseCancellationServiceImpl
        implements AiConversationDirectResponseCancellationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            AiConversationDirectResponseCancellationServiceImpl.class);

    private final AiModelUsageDetailMapper usageDetailMapper;
    private final AiModelUsageMapper usageMapper;
    private final AiConversationIdempotencyHasher idempotencyHasher;
    private final AiConversationDirectResponseControlStore controlStore;
    private final AiConversationDirectResponseActiveRegistry activeRegistry;
    private final AiConversationDirectResponseControlPublisher controlPublisher;
    private final AiConversationAsyncGenerationProperties properties;

    public AiConversationDirectResponseCancellationServiceImpl(
            AiModelUsageDetailMapper usageDetailMapper,
            AiModelUsageMapper usageMapper,
            AiConversationIdempotencyHasher idempotencyHasher,
            AiConversationDirectResponseControlStore controlStore,
            AiConversationDirectResponseActiveRegistry activeRegistry,
            AiConversationDirectResponseControlPublisher controlPublisher,
            AiConversationAsyncGenerationProperties properties) {
        this.usageDetailMapper = Objects.requireNonNull(usageDetailMapper);
        this.usageMapper = Objects.requireNonNull(usageMapper);
        this.idempotencyHasher = Objects.requireNonNull(idempotencyHasher);
        this.controlStore = Objects.requireNonNull(controlStore);
        this.activeRegistry = Objects.requireNonNull(activeRegistry);
        this.controlPublisher = Objects.requireNonNull(controlPublisher);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public AiConversationDirectResponseCancellationStatus requestUserStop(
            long userId,
            UUID idempotencyKey,
            String traceId) {
        HmacIdentifier requestIdentifier =
                idempotencyHasher.identifier(userId, idempotencyKey);
        Duration controlTtl = controlTtl();
        // 先写意图再查询 Owner，覆盖 accepted 尚未到达或上游订阅尚未建立的竞态窗口。
        controlStore.requestUserStop(requestIdentifier, controlTtl);

        AiModelUsageDetail detail = usageDetailMapper.findByIdempotencyDigest(
                idempotencyHasher.digest(userId, idempotencyKey));
        if (detail == null || detail.getUsageId() == null) {
            return AiConversationDirectResponseCancellationStatus.NOT_ACTIVE;
        }
        AiModelUsage usage = usageMapper.findById(detail.getUsageId());
        if (usage == null
                || !Objects.equals(usage.getLoginIdentityId(), userId)) {
            return AiConversationDirectResponseCancellationStatus.NOT_ACTIVE;
        }
        if (!Objects.equals(
                usage.getBillingStatus(), AiModelBillingStatus.RESERVED.code())) {
            controlStore.clearUserStop(requestIdentifier);
            return AiConversationDirectResponseCancellationStatus.ALREADY_TERMINAL;
        }

        String requestIdentifierValue = requestIdentifier.value();
        if (activeRegistry.cancel(
                requestIdentifierValue,
                AiConversationInterruptionSource.USER_STOP)) {
            return AiConversationDirectResponseCancellationStatus.CANCEL_REQUESTED;
        }
        Optional<String> owner = controlStore.findOwner(requestIdentifier);
        if (owner.isPresent()
                && !properties.instanceId().equals(owner.get())) {
            try {
                controlPublisher.publishCancelRequested(
                        requestIdentifierValue,
                        owner.get(),
                        traceId);
            } catch (RuntimeException failure) {
                // Redis 意图和浏览器 Abort 仍会使 Owner 的流在终态按 USER_STOP 收敛，Rabbit 失败不伪装成成功取消。
                LOGGER.warn(
                        "event=ai_direct_response_cancel_route_failed traceId={} owner={} cause={}",
                        safeLogValue(traceId),
                        safeLogValue(owner.get()),
                        failure.getClass().getSimpleName());
            }
        }
        return AiConversationDirectResponseCancellationStatus.CANCEL_REQUESTED;
    }

    private Duration controlTtl() {
        return properties.maxWorkerDuration().plus(Duration.ofMinutes(1));
    }

    private static String safeLogValue(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}")
                ? value
                : "unavailable";
    }
}
