package com.example.temperate.service.user.apikey.management.impl;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.ai.AiModelMapper;
import com.example.temperate.mapper.ai.UserApiKeyMapper;
import com.example.temperate.mapper.ai.UserApiKeyModelMapper;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.ai.entity.ApiKeyModelGrantView;
import com.example.temperate.model.ai.entity.UserApiKey;
import com.example.temperate.service.user.apikey.bloom.ApiKeyBloomService;
import com.example.temperate.service.user.apikey.cache.ApiKeyAuthenticationCache;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.service.user.apikey.credential.ApiKeyCredentialService;
import com.example.temperate.service.user.apikey.credential.GeneratedApiKey;
import com.example.temperate.service.user.apikey.management.ApiKeyCursorCodec;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementErrorCode;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementException;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.CreateCommand;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Created;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Detail;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.ModelGrant;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Page;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.ReplaceModelsCommand;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Status;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Summary;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.UpdateCommand;
import com.example.temperate.service.user.apikey.management.UserApiKeyService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 该实现是来在 PostgreSQL 本地事务中编排 API Key CRUD 和模型授权全量替换，并在提交后失效缓存、收敛 Bloom，绝不执行物理删除。
 */
@Service
public final class UserApiKeyServiceImpl implements UserApiKeyService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(UserApiKeyServiceImpl.class);
    private static final int STATUS_DISABLED = 0;
    private static final int STATUS_ENABLED = 1;
    private static final int STATUS_DELETED = 2;

    private final UserApiKeyMapper apiKeyMapper;
    private final UserApiKeyModelMapper modelGrantMapper;
    private final AiModelMapper modelMapper;
    private final UserLoginIdentityMapper identityMapper;
    private final ApiKeyCredentialService credentialService;
    private final ApiKeyAuthenticationCache authenticationCache;
    private final ApiKeyBloomService bloomService;
    private final ApiKeyProperties properties;
    private final PublicIdCodec publicIdCodec;
    private final ApiKeyCursorCodec cursorCodec;
    private final Clock clock;

    public UserApiKeyServiceImpl(
            UserApiKeyMapper apiKeyMapper,
            UserApiKeyModelMapper modelGrantMapper,
            AiModelMapper modelMapper,
            UserLoginIdentityMapper identityMapper,
            ApiKeyCredentialService credentialService,
            ApiKeyAuthenticationCache authenticationCache,
            ApiKeyBloomService bloomService,
            ApiKeyProperties properties,
            PublicIdCodec publicIdCodec,
            Clock clock) {
        this.apiKeyMapper = Objects.requireNonNull(apiKeyMapper);
        this.modelGrantMapper = Objects.requireNonNull(modelGrantMapper);
        this.modelMapper = Objects.requireNonNull(modelMapper);
        this.identityMapper = Objects.requireNonNull(identityMapper);
        this.credentialService = Objects.requireNonNull(credentialService);
        this.authenticationCache = Objects.requireNonNull(authenticationCache);
        this.bloomService = Objects.requireNonNull(bloomService);
        this.properties = Objects.requireNonNull(properties);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.cursorCodec = new ApiKeyCursorCodec();
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public Created create(long loginIdentityId, CreateCommand command) {
        requireEnabled();
        requireUser(loginIdentityId);
        if (!identityMapper.existsById(loginIdentityId)) {
            throw notFound();
        }
        if (command == null) {
            throw invalid("API Key create command is required");
        }
        OffsetDateTime now = now();
        validateFutureExpiry(command.expiresAt(), now);
        List<Long> modelIds = decodeAndValidateModels(command.modelPublicIds(), 1);
        GeneratedApiKey generated = credentialService.generate();

        // 数据库新增前先把 Bloom 置为 DEGRADED；进程崩溃时认证会回源，而不会错误拒绝新 Key。
        ApiKeyBloomService.PositiveMutation mutation =
                bloomService.beginPositiveMutation(generated.digest());
        registerPositiveMutation(generated.digest(), mutation);

        UserApiKey entity = new UserApiKey();
        entity.setLoginIdentityId(loginIdentityId);
        entity.setKeyDigest(generated.digest());
        entity.setKeyHint(generated.hint());
        entity.setStatus(STATUS_ENABLED);
        entity.setExpiresAt(command.expiresAt());
        if (apiKeyMapper.insert(entity) != 1 || entity.getId() == null) {
            throw new IllegalStateException("API Key insert did not affect exactly one row");
        }
        if (modelGrantMapper.upsertActiveBatch(entity.getId(), modelIds, now) != modelIds.size()) {
            throw new IllegalStateException("API Key model grants were not fully inserted");
        }
        UserApiKey persisted = requireOwned(entity.getId(), loginIdentityId);
        Detail detail = toDetail(persisted, modelGrantMapper.findActiveModelDetails(entity.getId()), now);
        return new Created(detail, generated.plaintext());
    }

    @Override
    @Transactional(readOnly = true)
    public Page list(long loginIdentityId, String cursor, int pageSize) {
        requireEnabled();
        requireUser(loginIdentityId);
        if (pageSize < 1 || pageSize > 100) {
            throw invalid("API Key page size must be between 1 and 100");
        }
        ApiKeyCursorCodec.Cursor decoded = cursor == null || cursor.isBlank()
                ? null
                : cursorCodec.decode(cursor);
        List<UserApiKey> rows = apiKeyMapper.findOwnedPage(
                loginIdentityId,
                decoded == null ? null : decoded.createdAt(),
                decoded == null ? null : decoded.id(),
                pageSize + 1);
        boolean hasNext = rows.size() > pageSize;
        List<UserApiKey> page = hasNext ? rows.subList(0, pageSize) : rows;
        OffsetDateTime now = now();
        List<Summary> items = page.stream().map(value -> toSummary(value, now)).toList();
        String nextCursor = hasNext
                ? cursorCodec.encode(page.get(page.size() - 1).getCreatedAt(),
                page.get(page.size() - 1).getId())
                : null;
        return new Page(items, nextCursor);
    }

    @Override
    @Transactional(readOnly = true)
    public Detail detail(long loginIdentityId, String apiKeyPublicId) {
        requireEnabled();
        long id = decodePublicId(apiKeyPublicId);
        UserApiKey entity = apiKeyMapper.findOwnedById(id, loginIdentityId);
        if (entity == null || entity.getStatus() == STATUS_DELETED) {
            throw notFound();
        }
        return toDetail(entity, modelGrantMapper.findActiveModelDetails(id), now());
    }

    @Override
    @Transactional
    public Detail update(
            long loginIdentityId,
            String apiKeyPublicId,
            long expectedVersion,
            UpdateCommand command) {
        requireEnabled();
        if (command == null || command.status() == null) {
            throw invalid("API Key update command is invalid");
        }
        OffsetDateTime now = now();
        validateFutureExpiry(command.expiresAt(), now);
        UserApiKey current = lockOwned(apiKeyPublicId, loginIdentityId);
        requireVersion(current, expectedVersion);
        int nextStatus = command.status() == Status.ENABLED
                ? STATUS_ENABLED : STATUS_DISABLED;
        boolean changed = current.getStatus() != nextStatus
                || !Objects.equals(current.getExpiresAt(), command.expiresAt());
        if (!changed) {
            return toDetail(current, modelGrantMapper.findActiveModelDetails(current.getId()), now);
        }

        boolean wasEffective = isEffective(current.getStatus(), current.getExpiresAt(), now);
        boolean willBeEffective = isEffective(nextStatus, command.expiresAt(), now);
        ApiKeyBloomService.PositiveMutation mutation = null;
        if (!wasEffective && willBeEffective) {
            mutation = bloomService.beginPositiveMutation(current.getKeyDigest());
            registerPositiveMutation(current.getKeyDigest(), mutation);
        } else {
            registerAfterCommitInvalidation(current.getKeyDigest(), wasEffective && !willBeEffective);
        }
        int updated = apiKeyMapper.updateLifecycle(
                current.getId(),
                loginIdentityId,
                expectedVersion,
                nextStatus,
                command.expiresAt());
        if (updated != 1) {
            throw versionConflict();
        }
        UserApiKey persisted = requireOwned(current.getId(), loginIdentityId);
        return toDetail(
                persisted,
                modelGrantMapper.findActiveModelDetails(current.getId()),
                now);
    }

    @Override
    @Transactional
    public Detail replaceModels(
            long loginIdentityId,
            String apiKeyPublicId,
            long expectedVersion,
            ReplaceModelsCommand command) {
        requireEnabled();
        if (command == null || command.modelPublicIds() == null) {
            throw invalid("API Key model replacement command is required");
        }
        UserApiKey current = lockOwned(apiKeyPublicId, loginIdentityId);
        requireVersion(current, expectedVersion);
        List<Long> desired = decodeAndValidateModels(command.modelPublicIds(), 0);
        Set<Long> existing = new LinkedHashSet<>(
                modelGrantMapper.findActiveModelIds(current.getId()));
        if (existing.equals(new LinkedHashSet<>(desired))) {
            return toDetail(current, modelGrantMapper.findActiveModelDetails(current.getId()), now());
        }

        OffsetDateTime now = now();
        int expectedRevoked = (int) existing.stream()
                .filter(modelId -> !desired.contains(modelId))
                .count();
        if (desired.isEmpty()) {
            if (modelGrantMapper.revokeAll(current.getId(), now) != expectedRevoked) {
                throw new IllegalStateException(
                        "API Key model revocation count did not match the locked snapshot");
            }
        } else {
            if (modelGrantMapper.revokeMissing(current.getId(), desired, now)
                    != expectedRevoked
                    || modelGrantMapper.upsertActiveBatch(current.getId(), desired, now)
                    != desired.size()) {
                throw new IllegalStateException(
                        "API Key model replacement did not fully apply");
            }
        }
        if (apiKeyMapper.incrementRowVersion(
                current.getId(), loginIdentityId, expectedVersion) != 1) {
            throw versionConflict();
        }
        registerAfterCommitInvalidation(current.getKeyDigest(), false);
        UserApiKey persisted = requireOwned(current.getId(), loginIdentityId);
        return toDetail(
                persisted,
                modelGrantMapper.findActiveModelDetails(current.getId()),
                now);
    }

    @Override
    @Transactional
    public void delete(
            long loginIdentityId,
            String apiKeyPublicId,
            long expectedVersion) {
        requireEnabled();
        long id = decodePublicId(apiKeyPublicId);
        UserApiKey current = apiKeyMapper.findOwnedByIdForUpdate(id, loginIdentityId);
        if (current == null) {
            throw notFound();
        }
        if (current.getStatus() == STATUS_DELETED) {
            return;
        }
        requireVersion(current, expectedVersion);
        OffsetDateTime now = now();
        int activeGrantCount = modelGrantMapper.findActiveModelIds(id).size();
        if (modelGrantMapper.revokeAll(id, now) != activeGrantCount) {
            throw new IllegalStateException(
                    "API Key delete did not revoke every active model grant");
        }
        if (apiKeyMapper.softDelete(
                id, loginIdentityId, expectedVersion, now) != 1) {
            throw versionConflict();
        }
        registerAfterCommitInvalidation(current.getKeyDigest(), true);
    }

    private List<Long> decodeAndValidateModels(
            List<String> publicIds,
            int minimumSize) {
        if (publicIds == null
                || publicIds.size() < minimumSize
                || publicIds.size() > properties.getMaxModelGrants()) {
            throw invalid("API Key model grant count is invalid");
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String publicId : publicIds) {
            long decoded;
            try {
                decoded = publicIdCodec.decode(publicId);
            } catch (IllegalArgumentException exception) {
                throw invalid("API Key model public ID is invalid");
            }
            if (!ids.add(decoded)) {
                throw invalid("API Key model public IDs must not contain duplicates");
            }
        }
        List<Long> normalized = List.copyOf(ids);
        if (!normalized.isEmpty()
                && modelMapper.findEnabledIdsForShare(normalized).size()
                != normalized.size()) {
            throw new ApiKeyManagementException(
                    ApiKeyManagementErrorCode.MODEL_NOT_FOUND_OR_DISABLED,
                    "One or more API Key models are unavailable");
        }
        return normalized;
    }

    private UserApiKey lockOwned(String publicId, long loginIdentityId) {
        long id = decodePublicId(publicId);
        UserApiKey entity = apiKeyMapper.findOwnedByIdForUpdate(id, loginIdentityId);
        if (entity == null || entity.getStatus() == STATUS_DELETED) {
            throw notFound();
        }
        return entity;
    }

    private UserApiKey requireOwned(long id, long loginIdentityId) {
        UserApiKey entity = apiKeyMapper.findOwnedById(id, loginIdentityId);
        if (entity == null) {
            throw new IllegalStateException("API Key disappeared inside its transaction");
        }
        return entity;
    }

    private long decodePublicId(String publicId) {
        try {
            return publicIdCodec.decode(publicId);
        } catch (IllegalArgumentException exception) {
            throw new ApiKeyManagementException(
                    ApiKeyManagementErrorCode.PUBLIC_ID_INVALID,
                    "API Key public ID is invalid");
        }
    }

    private void registerPositiveMutation(
            byte[] digest,
            ApiKeyBloomService.PositiveMutation mutation) {
        String identifier = credentialService.digestIdentifier(digest);
        // afterCompletion 同时覆盖 SQL 异常和显式回滚，保证 pending mutation 不因普通回滚永久遗留。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // PostgreSQL 已提交后不能再把回调异常暴露成“创建失败”，否则完整 Key 已丢失但数据库记录仍然存在。
                try {
                    bloomService.commitPositiveMutation(mutation);
                } catch (RuntimeException exception) {
                    logAfterCommitFailure("positive_mutation_commit", exception);
                }
                authenticationCache.invalidate(identifier);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        bloomService.rollbackPositiveMutation(mutation);
                    } catch (RuntimeException exception) {
                        // 撤销失败会让 pending mutation 保持 DEGRADED，后续 Leader 重建仍会安全回源数据库。
                        logAfterCommitFailure("positive_mutation_rollback", exception);
                    }
                }
            }
        });
    }

    private void registerAfterCommitInvalidation(byte[] digest, boolean removeFromBloom) {
        String identifier = credentialService.digestIdentifier(digest);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                authenticationCache.invalidate(identifier);
                if (removeFromBloom) {
                    try {
                        bloomService.remove(digest);
                    } catch (RuntimeException exception) {
                        // 删除计数失败只形成假阳性；已提交的禁用或软删除结果不得被回调异常伪装成失败。
                        logAfterCommitFailure("bloom_remove", exception);
                    }
                }
            }
        });
    }

    private static void logAfterCommitFailure(
            String operation,
            RuntimeException exception) {
        LOGGER.warn(
                "event=api_key_after_commit_callback_failed traceId={} operation={} cause={}",
                traceId(),
                operation,
                exception.getClass().getSimpleName());
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value == null || value.isBlank() ? "background" : value;
    }

    private Detail toDetail(
            UserApiKey key,
            List<ApiKeyModelGrantView> modelViews,
            OffsetDateTime now) {
        List<ModelGrant> models = modelViews.stream()
                .map(model -> new ModelGrant(
                        publicIdCodec.encode(model.getAiModelId()),
                        model.getModelName(),
                        model.getVendor(),
                        Boolean.TRUE.equals(model.getEnabled())))
                .toList();
        return new Detail(toSummary(key, now), models);
    }

    private Summary toSummary(UserApiKey key, OffsetDateTime now) {
        return new Summary(
                publicIdCodec.encode(key.getId()),
                credentialService.mask(key.getKeyHint()),
                key.getStatus() == STATUS_ENABLED ? Status.ENABLED : Status.DISABLED,
                key.getExpiresAt(),
                key.getExpiresAt() != null && !key.getExpiresAt().isAfter(now),
                key.getLastUsedAt(),
                key.getCreatedAt(),
                key.getUpdatedAt(),
                key.getRowVersion());
    }

    private static void requireVersion(UserApiKey current, long expectedVersion) {
        if (expectedVersion < 0 || current.getRowVersion() != expectedVersion) {
            throw versionConflict();
        }
    }

    private static boolean isEffective(
            int status,
            OffsetDateTime expiresAt,
            OffsetDateTime now) {
        return status == STATUS_ENABLED
                && (expiresAt == null || expiresAt.isAfter(now));
    }

    private static void validateFutureExpiry(
            OffsetDateTime expiresAt,
            OffsetDateTime now) {
        if (expiresAt != null
                && (!expiresAt.isAfter(now)
                || !ZoneOffset.UTC.equals(expiresAt.getOffset()))) {
            throw invalid("API Key expiry must be a future UTC instant");
        }
    }

    private static void requireUser(long loginIdentityId) {
        if (loginIdentityId <= 0) {
            throw invalid("Authenticated user ID is invalid");
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new ApiKeyManagementException(
                    ApiKeyManagementErrorCode.FEATURE_DISABLED,
                    "API Key management is disabled");
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static ApiKeyManagementException invalid(String message) {
        return new ApiKeyManagementException(ApiKeyManagementErrorCode.INPUT_INVALID, message);
    }

    private static ApiKeyManagementException notFound() {
        return new ApiKeyManagementException(
                ApiKeyManagementErrorCode.API_KEY_NOT_FOUND,
                "API Key was not found");
    }

    private static ApiKeyManagementException versionConflict() {
        return new ApiKeyManagementException(
                ApiKeyManagementErrorCode.VERSION_CONFLICT,
                "API Key row version does not match");
    }
}
