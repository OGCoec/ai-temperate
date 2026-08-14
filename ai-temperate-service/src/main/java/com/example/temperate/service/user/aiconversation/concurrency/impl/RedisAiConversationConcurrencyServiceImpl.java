package com.example.temperate.service.user.aiconversation.concurrency.impl;

import com.example.temperate.service.user.aiconversation.concurrency.AiConversationConcurrencyPermit;
import com.example.temperate.service.user.aiconversation.concurrency.AiConversationConcurrencyService;
import com.example.temperate.service.user.aiinference.concurrency.AiInferenceConcurrencyPermit;
import com.example.temperate.service.user.aiinference.concurrency.AiInferenceConcurrencyService;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 该兼容实现是来把现有 H5/Android 会话调用适配到统一 AI 推理并发池，使它们与 API Key 共享相同账号和全局 Redis 物理 Key。
 */
@Service
public final class RedisAiConversationConcurrencyServiceImpl
        implements AiConversationConcurrencyService {

    private final AiInferenceConcurrencyService inferenceConcurrencyService;

    public RedisAiConversationConcurrencyServiceImpl(
            AiInferenceConcurrencyService inferenceConcurrencyService) {
        this.inferenceConcurrencyService = Objects.requireNonNull(inferenceConcurrencyService);
    }

    @Override
    public Optional<AiConversationConcurrencyPermit> tryAcquire(long userId, short weight) {
        AiInferenceConcurrencyService.AcquireResult result =
                inferenceConcurrencyService.tryAcquireAccount(userId, weight);
        if (result.result() != AiInferenceConcurrencyService.Result.ACQUIRED) {
            return Optional.empty();
        }
        AiInferenceConcurrencyPermit permit = result.permit();
        return Optional.of(new AiConversationConcurrencyPermit(
                permit.accountIdentifier(), permit.owner(), permit.weight()));
    }

    @Override
    public boolean renew(AiConversationConcurrencyPermit permit) {
        return inferenceConcurrencyService.renew(toInferencePermit(permit));
    }

    @Override
    public void release(AiConversationConcurrencyPermit permit) {
        inferenceConcurrencyService.release(toInferencePermit(permit));
    }

    private static AiInferenceConcurrencyPermit toInferencePermit(
            AiConversationConcurrencyPermit permit) {
        return new AiInferenceConcurrencyPermit(
                permit.userIdentifier(),
                null,
                permit.owner(),
                permit.weight());
    }
}
