package com.example.temperate.service.risk.ip2location.service.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.ip2location.domain.AcquiredIp2LocationKey;
import com.example.temperate.service.risk.ip2location.domain.Ip2LocationImportMode;
import com.example.temperate.service.risk.ip2location.domain.Ip2LocationKeyMaterial;
import com.example.temperate.service.risk.ip2location.domain.Ip2LocationPlanType;
import com.example.temperate.service.risk.ip2location.domain.ProtectedIp2LocationKey;
import com.example.temperate.service.risk.ip2location.dto.Ip2LocationKeyBatchCommand;
import com.example.temperate.service.risk.ip2location.dto.Ip2LocationKeyBatchResult;
import com.example.temperate.service.risk.ip2location.dto.Ip2LocationKeyPage;
import com.example.temperate.service.risk.ip2location.dto.Ip2LocationKeyView;
import com.example.temperate.service.risk.ip2location.security.Ip2LocationApiKeyProtector;
import com.example.temperate.service.risk.ip2location.service.Ip2LocationApiKeyService;
import com.example.temperate.service.risk.ip2location.store.Ip2LocationApiKeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 编排 IP2Location API Key 的批量验证、AES-GCM保护、Redis原子写入和调用侧解密。
 *
 * <p>管理员只能获得掩码元数据；供应商实现每次只能领取一个已扣减额度的明文 Key，且明文不会跨越该业务边界。</p>
 */
@Service
public final class Ip2LocationApiKeyServiceImpl implements Ip2LocationApiKeyService {

    private static final int MAX_BATCH_SIZE = 500;
    private static final long MAX_INITIAL_QUOTA = 10_000_000L;
    private static final Duration FREE_PLAN_VALIDITY = Duration.ofDays(7);

    private final Ip2LocationApiKeyStore store;
    private final Ip2LocationApiKeyProtector protector;
    private final Clock clock;

    public Ip2LocationApiKeyServiceImpl(
            Ip2LocationApiKeyStore store,
            Ip2LocationApiKeyProtector protector,
            Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.protector = Objects.requireNonNull(protector);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Ip2LocationKeyBatchResult importBatch(Ip2LocationKeyBatchCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.planType() == null) {
            throw new IllegalArgumentException("IP2Location plan type is required.");
        }
        if (command.initialQuota() <= 0 || command.initialQuota() > MAX_INITIAL_QUOTA) {
            throw new IllegalArgumentException("IP2Location initial quota is invalid.");
        }
        Instant now = clock.instant();
        // 有效期只能由服务端可信时钟和受控套餐枚举导出，禁止客户端通过伪造截止时间延长 Redis 凭据寿命。
        Instant expiresAt = expirationFor(command.planType(), now);
        List<String> rawKeys = command.apiKeys();
        if (rawKeys == null || rawKeys.isEmpty() || rawKeys.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("IP2Location batch size must be between 1 and 500.");
        }
        Ip2LocationImportMode mode = command.mode() == null
                ? Ip2LocationImportMode.CREATE_ONLY
                : command.mode();

        // 先完成全部规范化、加密和确定性去重，再执行一次 Lua；任意输入错误都不会产生部分写入。
        Map<String, ProtectedIp2LocationKey> unique = new LinkedHashMap<>();
        int inputDuplicates = 0;
        for (String rawKey : rawKeys) {
            ProtectedIp2LocationKey protectedKey = protector.protect(
                    rawKey,
                    command.planType(),
                    now,
                    expiresAt);
            if (unique.putIfAbsent(
                    protectedKey.keyId().value(),
                    protectedKey) != null) {
                inputDuplicates++;
            }
        }
        Ip2LocationApiKeyStore.BatchWriteResult result = store.writeBatch(
                List.copyOf(unique.values()),
                command.initialQuota(),
                mode);
        return new Ip2LocationKeyBatchResult(
                unique.size(),
                result.createdCount(),
                result.updatedCount(),
                result.duplicateCount() + inputDuplicates);
    }

    @Override
    public Ip2LocationKeyPage list(long cursor, int size) {
        Ip2LocationApiKeyStore.EncryptedPage encryptedPage = store.scan(cursor, size);
        List<Ip2LocationKeyView> views = new ArrayList<>(encryptedPage.entries().size());
        List<HmacIdentifier> invalid = new ArrayList<>();
        for (Ip2LocationApiKeyStore.EncryptedEntry entry : encryptedPage.entries()) {
            try {
                Ip2LocationKeyMaterial material =
                        protector.unprotect(entry.keyId(), entry.encryptedEnvelope());
                views.add(new Ip2LocationKeyView(
                        entry.keyId().value(),
                        material.maskedKey(),
                        material.planType(),
                        entry.remainingQuota(),
                        material.expiresAt(),
                        material.createdAt()));
            } catch (IllegalArgumentException exception) {
                // 无法认证的密文不能继续留在可随机领取的池中，删除时仍不记录具体字段或密文。
                invalid.add(entry.keyId());
            }
        }
        if (!invalid.isEmpty()) {
            store.delete(invalid);
        }
        return new Ip2LocationKeyPage(encryptedPage.nextCursor(), List.copyOf(views));
    }

    @Override
    public long delete(List<HmacIdentifier> keyIds) {
        return store.delete(keyIds);
    }

    @Override
    public Optional<AcquiredIp2LocationKey> acquire() {
        Optional<Ip2LocationApiKeyStore.AcquiredEnvelope> acquired = store.acquire();
        if (acquired.isEmpty()) {
            return Optional.empty();
        }
        Ip2LocationApiKeyStore.AcquiredEnvelope envelope = acquired.get();
        try {
            Ip2LocationKeyMaterial material =
                    protector.unprotect(envelope.keyId(), envelope.encryptedEnvelope());
            if (!material.expiresAt().isAfter(clock.instant())) {
                store.delete(List.of(envelope.keyId()));
                return Optional.empty();
            }
            return Optional.of(new AcquiredIp2LocationKey(
                    envelope.keyId(),
                    material.apiKey(),
                    envelope.remainingQuota()));
        } catch (IllegalArgumentException exception) {
            store.delete(List.of(envelope.keyId()));
            return Optional.empty();
        }
    }

    @Override
    public void discard(HmacIdentifier keyId) {
        store.delete(List.of(Objects.requireNonNull(keyId)));
    }

    private static Instant expirationFor(
            Ip2LocationPlanType planType,
            Instant now) {
        return switch (planType) {
            case FREE -> now.plus(FREE_PLAN_VALIDITY);
            case STARTER, PLUS, SECURITY, SECURITY_TRIAL, CUSTOM ->
                    now.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
        };
    }
}
