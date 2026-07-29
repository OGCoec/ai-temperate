package com.example.temperate.service.admin.mailinspection.job.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobReservation;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobReservationStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobSnapshot;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobSummary;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.event.MailInspectionJobEvent;
import com.example.temperate.service.admin.mailinspection.event.MailInspectionJobEventPublisher;
import com.example.temperate.service.admin.mailinspection.event.MailInspectionJobEventType;
import com.example.temperate.service.admin.mailinspection.job.AdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.job.MailInspectionAcceptanceState;
import com.example.temperate.service.admin.mailinspection.job.RedisAdminMailInspectionJobStoreSupport;
import com.example.temperate.service.admin.mailinspection.job.redis.MailInspectionJobKeyHasher;
import com.example.temperate.service.admin.mailinspection.job.redis.MailInspectionRedisJobCodec;
import com.example.temperate.service.admin.mailinspection.job.redis.MailInspectionRedisJobDocument;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 使用 Redis Hash、结果桶和 Lua 实现邮件检查任务的唯一权威状态。
 *
 * <p>公开 Job ID 只在 Value 中保存，所有 Key 均使用独立 HMAC；创建、领取、计数、终态和过期时间修改由 Lua 原子完成。
 * 读取不会续租，活动租约只由后端定时心跳刷新，终态则使用固定十五分钟保留期。</p>
 */
@Component
public final class RedisAdminMailInspectionJobStore
        implements AdminMailInspectionJobStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            RedisAdminMailInspectionJobStore.class);
    private static final int META_BATCH_JOB_LIMIT = 250;
    private static final RedisScript<String> RESERVE =
            stringScript("reserve-job.lua");
    private static final RedisScript<List> UPDATE_SUBMISSION =
            listScript("update-submission.lua");
    private static final RedisScript<List> CLAIM_WORK =
            listScript("claim-work.lua");
    private static final RedisScript<List> RECORD_RESULT =
            listScript("record-result.lua");
    private static final RedisScript<List> CHANGE_STATUS =
            listScript("change-status.lua");
    private static final RedisScript<List> MARK_TERMINAL =
            listScript("mark-terminal.lua");
    private static final RedisScript<List> REFRESH_LEASE =
            listScript("refresh-active-leases.lua");
    private static final RedisScript<String> CHANGE_ACCEPTANCE =
            stringScript("change-acceptance-state.lua");
    private static final RedisScript<Long> RESTORE =
            longScript("restore-pending-job.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final MailInspectionJobKeyHasher keyHasher;
    private final MailInspectionRedisJobCodec codec;
    private final AdminMailInspectionProperties properties;
    private final MailInspectionJobEventPublisher eventPublisher;
    private final Clock clock;

    public RedisAdminMailInspectionJobStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            MailInspectionJobKeyHasher keyHasher,
            MailInspectionRedisJobCodec codec,
            AdminMailInspectionProperties properties,
            MailInspectionJobEventPublisher eventPublisher,
            Clock clock) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.keyHasher = Objects.requireNonNull(keyHasher);
        this.codec = Objects.requireNonNull(codec);
        this.properties = Objects.requireNonNull(properties);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public MailInspectionJobReservation reserveOrFind(
            MailInspectionRedisJobDocument candidate,
            List<MailInspectionResult> immediateResults) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        List<MailInspectionResult> safeResults = immediateResults == null
                ? List.of()
                : List.copyOf(immediateResults);
        if (candidate.requestedCount() > properties.job().maxCredentialLines()) {
            throw new AdminException(
                    AdminErrorCode.ADMIN_MAIL_INSPECTION_INVALID_REQUEST,
                    "mail inspection credential line limit exceeded");
        }
        HmacIdentifier jobHash = keyHasher.hashJobId(candidate.jobId());
        if (!jobHash.value().equals(candidate.jobHash())) {
            throw new IllegalArgumentException(
                    "mail inspection candidate job hash is inconsistent");
        }
        HmacIdentifier requestHash =
                keyHasher.hashClientRequestId(candidate.clientRequestId());
        List<String> activeKeys = activeKeys();
        List<String> bucketKeys = bucketKeys(candidate);
        List<String> keys = new ArrayList<>();
        keys.add(keyFactory.adminMailInspectionJobIdempotencyKey(requestHash));
        keys.add(keyFactory.adminMailInspectionJobActiveKey(
                typeSegment(candidate.inspectionType())));
        keys.add(acceptanceKey(candidate.inspectionType()));
        keys.add(keyFactory.adminMailInspectionJobMetaKey(jobHash));
        keys.add(keyFactory.adminMailInspectionJobCountsKey(jobHash));
        keys.add(keyFactory.adminMailInspectionJobRevisionKey(jobHash));
        keys.addAll(activeKeys);
        keys.addAll(bucketKeys);

        Map<Integer, Integer> bucketKeyIndexes = new HashMap<>();
        for (int index = 0; index < bucketKeys.size(); index++) {
            bucketKeyIndexes.put(index, 7 + activeKeys.size() + index);
        }
        List<Object> arguments = new ArrayList<>();
        arguments.add(Integer.toString(activeKeys.size()));
        arguments.add(Integer.toString(properties.job().maxActiveJobs()));
        arguments.add(Long.toString(candidate.expiresAt().toEpochMilli()));
        arguments.add(Long.toString(
                candidate.submissionExpiresAt().toEpochMilli()));
        arguments.add(candidate.jobHash());
        arguments.add(candidate.requestFingerprint());
        arguments.add(Integer.toString(candidate.schemaVersion()));
        arguments.add(candidate.jobId());
        arguments.add(candidate.inspectionType().name());
        arguments.add(candidate.status().name());
        arguments.add(Integer.toString(candidate.requestedCount()));
        arguments.add(Integer.toString(candidate.acceptedCount()));
        arguments.add(Integer.toString(candidate.duplicateCount()));
        arguments.add(Integer.toString(candidate.invalidCount()));
        arguments.add(Integer.toString(candidate.businessConcurrency()));
        arguments.add(Integer.toString(candidate.completionTarget()));
        arguments.add(candidate.clientRequestId());
        arguments.add(candidate.requestFingerprint());
        arguments.add(Integer.toString(candidate.submissionChunkCount()));
        arguments.add(Boolean.toString(candidate.recoveredAfterRestart()));
        arguments.add(Boolean.toString(candidate.resultHistoryLost()));
        arguments.add(Integer.toString(candidate.lostResultCount()));
        arguments.add(Boolean.toString(candidate.resumeRequired()));
        arguments.add(codec.writePendingItems(candidate.pendingItems()));
        arguments.add(epoch(candidate.createdAt()));
        arguments.add(epoch(candidate.startedAt()));
        arguments.add(epoch(candidate.completedAt()));
        arguments.add(epoch(candidate.expiresAt()));
        arguments.add(epoch(candidate.submissionExpiresAt()));
        arguments.add(epoch(candidate.recoveredAt()));
        arguments.add(Integer.toString(safeResults.size()));
        for (MailInspectionResult result : safeResults) {
            int bucket = RedisAdminMailInspectionJobStoreSupport.bucketForLine(
                    result.lineNumber(),
                    properties.job().resultBucketSize());
            Integer keyIndex = bucketKeyIndexes.get(bucket);
            if (keyIndex == null) {
                throw new IllegalArgumentException(
                        "mail inspection result line exceeds job boundary");
            }
            arguments.add(Integer.toString(keyIndex));
            arguments.add(Integer.toString(result.lineNumber()));
            arguments.add(codec.writeResult(result));
            arguments.add(result.status().name());
        }

        String outcome = execute(
                RESERVE, keys, arguments.toArray());
        int separator = outcome.indexOf('|');
        String code = separator < 0 ? outcome : outcome.substring(0, separator);
        String resolvedHash = separator < 0
                ? candidate.jobHash()
                : outcome.substring(separator + 1);
        MailInspectionRedisJobDocument resolved =
                findMetaByHash(resolvedHash).orElse(candidate);
        return switch (code) {
            case "CREATED" -> {
                publish(
                        resolved,
                        1L,
                        resolved.status().terminal()
                                ? MailInspectionJobEventType.TERMINAL
                                : MailInspectionJobEventType.STATUS);
                yield new MailInspectionJobReservation(
                        MailInspectionJobReservationStatus.CREATED,
                        resolved);
            }
            case "REPLAYED" -> new MailInspectionJobReservation(
                    MailInspectionJobReservationStatus.REPLAYED,
                    requiredResolved(resolvedHash, resolved));
            case "FINGERPRINT_CONFLICT" -> new MailInspectionJobReservation(
                    MailInspectionJobReservationStatus.FINGERPRINT_CONFLICT,
                    requiredResolved(resolvedHash, resolved));
            case "TYPE_CAPACITY_CONFLICT" -> new MailInspectionJobReservation(
                    MailInspectionJobReservationStatus.TYPE_CAPACITY_CONFLICT,
                    candidate);
            case "UNAVAILABLE" -> throw unavailable(null);
            default -> throw unavailable(new IllegalStateException(
                    "unknown mail inspection reserve result"));
        };
    }

    @Override
    public Optional<MailInspectionJobSnapshot> findSnapshot(String jobId) {
        return findSnapshotMeta(jobId).map(document -> snapshot(
                document,
                readCounts(document),
                readResults(document, 0, properties.job().maxCredentialLines())));
    }

    @Override
    public Optional<MailInspectionRedisJobDocument> findSnapshotMeta(
            String jobId) {
        return findMetaByHash(keyHasher.hashJobId(jobId).value());
    }

    @Override
    public Map<String, MailInspectionRedisJobDocument> findSnapshotMetas(
            Set<String> jobIds) {
        Objects.requireNonNull(jobIds, "jobIds must not be null");
        List<String> requested = Set.copyOf(jobIds).stream()
                .map(Objects::requireNonNull)
                .sorted()
                .toList();
        Map<String, MailInspectionRedisJobDocument> resolved =
                new HashMap<>();
        for (int offset = 0;
                offset < requested.size();
                offset += META_BATCH_JOB_LIMIT) {
            List<String> batch = requested.subList(
                    offset,
                    Math.min(
                            requested.size(),
                            offset + META_BATCH_JOB_LIMIT));
            List<HmacIdentifier> hashes = batch.stream()
                    .map(keyHasher::hashJobId)
                    .toList();
            List<Object> responses;
            try {
                // 每个 Job 使用 HGETALL + GET revision，两百五十个 Job 恰好不超过五百条 Pipeline 命令。
                responses = redisTemplate.executePipelined(
                        new SessionCallback<>() {
                    @Override
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    public Object execute(RedisOperations operations) {
                        for (HmacIdentifier hash : hashes) {
                            operations.opsForHash().entries(
                                    keyFactory.adminMailInspectionJobMetaKey(
                                            hash));
                            operations.opsForValue().get(
                                    keyFactory
                                            .adminMailInspectionJobRevisionKey(
                                                    hash));
                        }
                        return null;
                    }
                });
            } catch (RuntimeException exception) {
                throw unavailable(exception);
            }
            if (responses.size() != batch.size() * 2) {
                throw unavailable(null);
            }
            for (int index = 0; index < batch.size(); index++) {
                Object metaValue = responses.get(index * 2);
                Object revisionValue = responses.get(index * 2 + 1);
                if (metaValue instanceof Map<?, ?> raw
                        && raw.isEmpty()
                        && revisionValue == null) {
                    continue;
                }
                if (!(metaValue instanceof Map<?, ?> raw)
                        || raw.isEmpty()
                        || revisionValue == null) {
                    throw unavailable(null);
                }
                MailInspectionRedisJobDocument document = document(
                        stringMap(raw),
                        number(revisionValue));
                String expectedHash = hashes.get(index).value();
                if (!expectedHash.equals(document.jobHash())
                        || !expectedHash.equals(
                                keyHasher.hashJobId(
                                        document.jobId()).value())) {
                    throw unavailable(null);
                }
                resolved.put(batch.get(index), document);
            }
        }
        return Map.copyOf(resolved);
    }

    @Override
    public List<MailInspectionResult> findResultBatch(
            String jobId, int offset, int limit) {
        if (offset < 0
                || limit < 1
                || limit > properties.job().snapshotBatchSize()) {
            throw new IllegalArgumentException(
                    "mail inspection result batch boundary is invalid");
        }
        MailInspectionRedisJobDocument document = findSnapshotMeta(jobId)
                .orElseThrow(() -> new AdminException(
                        AdminErrorCode.ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND,
                        "mail inspection job not found"));
        return readResults(document, offset, limit);
    }

    @Override
    public Optional<MailInspectionRedisJobDocument> findByClientRequestId(
            String clientRequestId) {
        HmacIdentifier requestHash =
                keyHasher.hashClientRequestId(clientRequestId);
        try {
            String value = redisTemplate.opsForValue().get(
                    keyFactory.adminMailInspectionJobIdempotencyKey(
                            requestHash));
            if (value == null) {
                return Optional.empty();
            }
            int separator = value.indexOf('|');
            return findMetaByHash(
                    separator < 0 ? value : value.substring(0, separator));
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public Optional<MailInspectionRedisJobDocument> findActiveByType(
            MailInspectionType type) {
        try {
            String jobHash = redisTemplate.opsForValue().get(
                    keyFactory.adminMailInspectionJobActiveKey(
                            typeSegment(type)));
            return jobHash == null
                    ? Optional.empty()
                    : findMetaByHash(jobHash).filter(
                            MailInspectionRedisJobDocument::active);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public List<MailInspectionRedisJobDocument> findActiveJobs() {
        return loadActiveDocuments();
    }

    @Override
    public List<MailInspectionJobSnapshot> findRecovered() {
        List<MailInspectionRedisJobDocument> recovered =
                loadActiveDocuments().stream()
                .filter(MailInspectionRedisJobDocument::recoveredAfterRestart)
                .filter(document -> document.status()
                        == MailInspectionJobStatus.AWAITING_ADMIN_RESUME
                        || document.status()
                        == MailInspectionJobStatus.AWAITING_CLIENT_RESUBMISSION
                        || document.status()
                        == MailInspectionJobStatus.RECOVERY_FAILED)
                .toList();
        return readSnapshotsWithoutResults(recovered).stream()
                .sorted(Comparator.comparing(MailInspectionJobSnapshot::createdAt))
                .toList();
    }

    @Override
    public List<MailInspectionRedisJobDocument> findIncompleteExpired(
            Instant now) {
        return loadActiveDocuments().stream()
                .filter(document -> document.status()
                        == MailInspectionJobStatus.DISPATCHING
                        || document.status()
                        == MailInspectionJobStatus.AWAITING_CLIENT_RESUBMISSION)
                .filter(document -> document.submissionExpiresAt() != null
                        && !document.submissionExpiresAt().isAfter(now))
                .sorted(Comparator.comparing(
                        MailInspectionRedisJobDocument::createdAt))
                .toList();
    }

    private List<MailInspectionJobSnapshot> readSnapshotsWithoutResults(
            List<MailInspectionRedisJobDocument> documents) {
        if (documents.isEmpty()) {
            return List.of();
        }
        List<Object> responses;
        try {
            // 恢复列表只需要计数和脱敏等待项；批量读取 Counts，禁止为每个任务逐次访问 Redis。
            responses =
                    redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public Object execute(RedisOperations operations) {
                    for (MailInspectionRedisJobDocument document : documents) {
                        HmacIdentifier hash =
                                HmacIdentifier.fromProtectedValue(
                                        document.jobHash());
                        operations.opsForHash().entries(
                                keyFactory.adminMailInspectionJobCountsKey(
                                        hash));
                    }
                    return null;
                }
            });
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        List<MailInspectionJobSnapshot> snapshots =
                new ArrayList<>(documents.size());
        for (int index = 0; index < documents.size(); index++) {
            Object response = responses.get(index);
            if (!(response instanceof Map<?, ?> counts)
                    || counts.isEmpty()) {
                throw unavailable(null);
            }
            snapshots.add(snapshot(
                    documents.get(index),
                    stringMap(counts),
                    List.of()));
        }
        return List.copyOf(snapshots);
    }

    @Override
    public boolean recordSubmissionConfirmed(
            String jobId, int chunkIndex, Instant now) {
        return updateSubmission(jobId, chunkIndex, now, "confirmed");
    }

    @Override
    public boolean isSubmissionChunkConfirmed(
            String jobId, int chunkIndex) {
        MailInspectionRedisJobDocument document = requiredMeta(jobId);
        if (chunkIndex < 0
                || chunkIndex >= document.submissionChunkCount()) {
            throw new IllegalArgumentException(
                    "mail inspection submission chunk index is invalid");
        }
        HmacIdentifier hash = HmacIdentifier.fromProtectedValue(
                document.jobHash());
        try {
            Object value = redisTemplate.opsForHash().get(
                    keyFactory.adminMailInspectionJobCountsKey(hash),
                    "confirmed:" + chunkIndex);
            return value != null;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public Set<Integer> confirmedSubmissionChunks(String jobId) {
        MailInspectionRedisJobDocument document = requiredMeta(jobId);
        HmacIdentifier hash = HmacIdentifier.fromProtectedValue(
                document.jobHash());
        try {
            Set<Object> fields = redisTemplate.opsForHash().keys(
                    keyFactory.adminMailInspectionJobCountsKey(hash));
            return fields.stream()
                    .map(RedisAdminMailInspectionJobStore::text)
                    .filter(value -> value.startsWith("confirmed:"))
                    .map(value -> Integer.parseInt(
                            value.substring("confirmed:".length())))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public boolean recordSubmissionDispatched(
            String jobId, int chunkIndex, Instant now) {
        return updateSubmission(jobId, chunkIndex, now, "dispatched");
    }

    @Override
    public boolean isSubmissionChunkDispatched(
            String jobId, int chunkIndex) {
        MailInspectionRedisJobDocument document = requiredMeta(jobId);
        if (chunkIndex < 0
                || chunkIndex >= document.submissionChunkCount()) {
            throw new IllegalArgumentException(
                    "mail inspection submission chunk index is invalid");
        }
        HmacIdentifier hash = HmacIdentifier.fromProtectedValue(
                document.jobHash());
        try {
            return redisTemplate.opsForHash().hasKey(
                    keyFactory.adminMailInspectionJobCountsKey(hash),
                    "dispatched:" + chunkIndex);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public boolean hasResult(String jobId, int lineNumber) {
        MailInspectionRedisJobDocument document = requiredMeta(jobId);
        validateLineNumber(document, lineNumber);
        int bucket = RedisAdminMailInspectionJobStoreSupport.bucketForLine(
                lineNumber,
                properties.job().resultBucketSize());
        HmacIdentifier hash = HmacIdentifier.fromProtectedValue(
                document.jobHash());
        try {
            return redisTemplate.opsForHash().hasKey(
                    keyFactory.adminMailInspectionJobResultBucketKey(
                            hash, bucket),
                    Integer.toString(lineNumber));
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public boolean claimLine(String jobId, int lineNumber, Instant now) {
        MailInspectionRedisJobDocument document = requiredMeta(jobId);
        validateLineNumber(document, lineNumber);
        HmacIdentifier hash = HmacIdentifier.fromProtectedValue(
                document.jobHash());
        int bucket = RedisAdminMailInspectionJobStoreSupport.bucketForLine(
                lineNumber,
                properties.job().resultBucketSize());
        List<String> keys = new ArrayList<>();
        keys.add(keyFactory.adminMailInspectionJobMetaKey(hash));
        keys.add(keyFactory.adminMailInspectionJobCountsKey(hash));
        keys.add(keyFactory.adminMailInspectionJobResultBucketKey(hash, bucket));
        keys.add(keyFactory.adminMailInspectionJobRevisionKey(hash));
        // 领取时间超过单项总截止时间后才允许重领，避免消费者崩溃留下永久 inflight，同时不与仍在期限内的业务执行并发。
        List<?> result = execute(
                CLAIM_WORK,
                keys,
                Integer.toString(lineNumber),
                Long.toString(now.toEpochMilli()),
                Long.toString(now.minus(
                        properties.oauth().credentialTimeout())
                        .toEpochMilli()));
        boolean changed = changedOrThrow(result);
        if (changed) {
            publish(document, number(result.get(1)),
                    MailInspectionJobEventType.PROGRESS);
        }
        return changed;
    }

    @Override
    public boolean recordResult(
            String jobId, MailInspectionResult value, Instant now) {
        MailInspectionRedisJobDocument document = requiredMeta(jobId);
        Objects.requireNonNull(value, "result must not be null");
        validateLineNumber(document, value.lineNumber());
        HmacIdentifier hash = HmacIdentifier.fromProtectedValue(
                document.jobHash());
        int bucket = RedisAdminMailInspectionJobStoreSupport.bucketForLine(
                value.lineNumber(),
                properties.job().resultBucketSize());
        List<String> keys = new ArrayList<>();
        keys.add(keyFactory.adminMailInspectionJobMetaKey(hash));
        keys.add(keyFactory.adminMailInspectionJobCountsKey(hash));
        keys.add(keyFactory.adminMailInspectionJobResultBucketKey(hash, bucket));
        keys.add(keyFactory.adminMailInspectionJobRevisionKey(hash));
        keys.add(keyFactory.adminMailInspectionJobActiveKey(
                typeSegment(document.inspectionType())));
        keys.addAll(allJobKeys(document));
        List<?> result = execute(
                RECORD_RESULT,
                keys,
                Integer.toString(value.lineNumber()),
                codec.writeResult(value),
                value.status().name(),
                Long.toString(now.toEpochMilli()),
                Long.toString(document.expiresAt().toEpochMilli()),
                Long.toString(terminalExpiry(now).toEpochMilli()));
        boolean changed = changedOrThrow(result);
        if (changed) {
            publish(
                    document,
                    number(result.get(1)),
                    number(result.get(2)) == 1
                            ? MailInspectionJobEventType.TERMINAL
                            : MailInspectionJobEventType.RESULT);
        }
        return changed;
    }

    @Override
    public boolean changeStatus(
            String jobId,
            Set<MailInspectionJobStatus> expected,
            MailInspectionJobStatus target,
            Instant now) {
        if (expected == null || expected.isEmpty() || target == null
                || target.terminal()) {
            throw new IllegalArgumentException(
                    "mail inspection status transition is invalid");
        }
        MailInspectionRedisJobDocument document = requiredMeta(jobId);
        HmacIdentifier hash = HmacIdentifier.fromProtectedValue(
                document.jobHash());
        List<String> keys = new ArrayList<>();
        keys.add(keyFactory.adminMailInspectionJobMetaKey(hash));
        keys.add(keyFactory.adminMailInspectionJobCountsKey(hash));
        keys.add(keyFactory.adminMailInspectionJobRevisionKey(hash));
        keys.addAll(allJobKeys(document));
        String expectedCsv = expected.stream()
                .map(Enum::name)
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        List<?> result = execute(
                CHANGE_STATUS,
                keys,
                expectedCsv,
                target.name(),
                Long.toString(now.toEpochMilli()),
                Long.toString(activeExpiry(now).toEpochMilli()),
                Boolean.toString(
                        target == MailInspectionJobStatus.AWAITING_ADMIN_RESUME
                                || target
                                == MailInspectionJobStatus.RECOVERY_FAILED));
        boolean changed = changedOrThrow(result);
        if (changed) {
            publish(document, number(result.get(1)),
                    MailInspectionJobEventType.STATUS);
        }
        return changed;
    }

    @Override
    public boolean markTerminal(
            String jobId,
            MailInspectionJobStatus terminalStatus,
            Instant now) {
        if (terminalStatus == null || !terminalStatus.terminal()) {
            throw new IllegalArgumentException(
                    "terminal mail inspection status is required");
        }
        MailInspectionRedisJobDocument document = requiredMeta(jobId);
        HmacIdentifier hash = HmacIdentifier.fromProtectedValue(
                document.jobHash());
        List<String> keys = new ArrayList<>();
        keys.add(keyFactory.adminMailInspectionJobMetaKey(hash));
        keys.add(keyFactory.adminMailInspectionJobCountsKey(hash));
        keys.add(keyFactory.adminMailInspectionJobRevisionKey(hash));
        keys.add(keyFactory.adminMailInspectionJobActiveKey(
                typeSegment(document.inspectionType())));
        keys.addAll(allJobKeys(document));
        List<?> result = execute(
                MARK_TERMINAL,
                keys,
                terminalStatus.name(),
                Long.toString(now.toEpochMilli()),
                Long.toString(terminalExpiry(now).toEpochMilli()));
        boolean changed = changedOrThrow(result);
        if (changed) {
            publish(document, number(result.get(1)),
                    MailInspectionJobEventType.TERMINAL);
        }
        return changed;
    }

    @Override
    public void refreshActiveLeases() {
        Instant expiresAt = activeExpiry(clock.instant());
        List<MailInspectionRedisJobDocument> active =
                loadActiveDocuments();
        if (active.isEmpty()) {
            return;
        }
        try {
            // 每个活动任务仍由独立 Lua 保证其 Key 同步过期，但整批脚本通过一个 Pipeline 提交，避免逐任务网络往返。
            List<Object> responses =
                    redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public Object execute(RedisOperations operations) {
                    for (MailInspectionRedisJobDocument document : active) {
                        HmacIdentifier hash =
                                HmacIdentifier.fromProtectedValue(
                                        document.jobHash());
                        List<String> keys = new ArrayList<>();
                        keys.add(keyFactory.adminMailInspectionJobMetaKey(hash));
                        keys.add(keyFactory.adminMailInspectionJobRevisionKey(hash));
                        keys.addAll(allJobKeys(document));
                        operations.execute(
                                REFRESH_LEASE,
                                keys,
                                Long.toString(expiresAt.toEpochMilli()));
                    }
                    return null;
                }
            });
            for (int index = 0; index < active.size(); index++) {
                Object response = responses.get(index);
                if (response instanceof List<?> values
                        && status(values) == 1) {
                    publish(
                            active.get(index),
                            number(values.get(1)),
                            MailInspectionJobEventType.PROGRESS);
                }
            }
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void restorePendingJob(
            MailInspectionRedisJobDocument document,
            List<MailInspectionResult> knownResults) {
        Objects.requireNonNull(document, "document must not be null");
        if (!document.active()) {
            throw new IllegalArgumentException(
                    "only active mail inspection jobs can be restored");
        }
        List<MailInspectionResult> safeResults =
                validateKnownResults(document, knownResults);
        HmacIdentifier hash = keyHasher.hashJobId(document.jobId());
        if (!hash.value().equals(document.jobHash())) {
            throw new IllegalArgumentException(
                    "mail inspection recovery job hash is inconsistent");
        }
        List<String> keys = List.of(
                keyFactory.adminMailInspectionJobMetaKey(hash),
                keyFactory.adminMailInspectionJobCountsKey(hash),
                keyFactory.adminMailInspectionJobRevisionKey(hash),
                keyFactory.adminMailInspectionJobActiveKey(
                        typeSegment(document.inspectionType())));
        List<Object> arguments = new ArrayList<>(31 + safeResults.size());
        java.util.Collections.addAll(
                arguments,
                Integer.toString(document.schemaVersion()),
                document.jobId(),
                document.jobHash(),
                document.inspectionType().name(),
                document.status().name(),
                Integer.toString(document.requestedCount()),
                Integer.toString(document.acceptedCount()),
                Integer.toString(document.duplicateCount()),
                Integer.toString(document.invalidCount()),
                Integer.toString(document.businessConcurrency()),
                Integer.toString(document.completionTarget()),
                nullToEmpty(document.clientRequestId()),
                nullToEmpty(document.requestFingerprint()),
                Integer.toString(document.submissionChunkCount()),
                Boolean.toString(document.recoveredAfterRestart()),
                Boolean.toString(document.resultHistoryLost()),
                Integer.toString(document.lostResultCount()),
                Boolean.toString(document.resumeRequired()),
                codec.writePendingItems(document.pendingItems()),
                epoch(document.createdAt()),
                epoch(document.startedAt()),
                epoch(document.completedAt()),
                epoch(document.expiresAt()),
                epoch(document.submissionExpiresAt()),
                epoch(document.recoveredAt()),
                Integer.toString(safeResults.size()),
                Integer.toString(Math.max(
                        0,
                        document.completionTarget() - safeResults.size())),
                "0",
                "0",
                Long.toString(Math.max(1L, document.revision())),
                Integer.toString(safeResults.size()));
        safeResults.forEach(result -> arguments.add(result.status().name()));
        long restored = execute(
                RESTORE,
                keys,
                arguments.toArray());
        if (restored < 0) {
            throw new AdminException(
                    AdminErrorCode.ADMIN_MAIL_INSPECTION_JOB_CONFLICT,
                    "mail inspection recovery conflicts with active task");
        }
        if (restored == 1 && !safeResults.isEmpty()) {
            writeKnownResults(document, safeResults);
        }
    }

    @Override
    public void changeAcceptanceState(
            MailInspectionType type,
            MailInspectionAcceptanceState state,
            String reason) {
        String previous = execute(
                CHANGE_ACCEPTANCE,
                List.of(acceptanceKey(type)),
                state.name());
        LOGGER.info(
                "event={} inspectionType={} previousState={} currentState={} reason={}",
                "admin_mail_inspection_acceptance_changed",
                type,
                previous == null || previous.isBlank() ? "ABSENT" : previous,
                state,
                reason);
    }

    @Override
    public void stopAllAccepting() {
        List<MailInspectionType> types = List.of(MailInspectionType.values());
        List<Object> responses;
        try {
            // 关闭闸门必须一次提交全部类型，避免进程退出路径在循环中产生四次独立 Redis 往返。
            responses = redisTemplate.executePipelined(
                    new SessionCallback<>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public Object execute(RedisOperations operations) {
                    for (MailInspectionType type : types) {
                        operations.execute(
                                CHANGE_ACCEPTANCE,
                                List.of(acceptanceKey(type)),
                                MailInspectionAcceptanceState.STOPPED.name());
                    }
                    return null;
                }
            });
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (responses.size() != types.size()) {
            throw unavailable(null);
        }
        for (int index = 0; index < types.size(); index++) {
            String previous = text(responses.get(index));
            LOGGER.info(
                    "event={} inspectionType={} previousState={} currentState={} reason={}",
                    "admin_mail_inspection_acceptance_changed",
                    types.get(index),
                    previous.isBlank() ? "ABSENT" : previous,
                    MailInspectionAcceptanceState.STOPPED,
                    "STOP_REQUESTED");
        }
    }

    @Override
    public MailInspectionAcceptanceState acceptanceState(
            MailInspectionType type) {
        try {
            String value = redisTemplate.opsForValue().get(
                    acceptanceKey(type));
            return value == null
                    ? MailInspectionAcceptanceState.RECOVERING
                    : MailInspectionAcceptanceState.valueOf(value);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private boolean updateSubmission(
            String jobId, int chunkIndex, Instant now, String mode) {
        MailInspectionRedisJobDocument document = requiredMeta(jobId);
        if (chunkIndex < 0
                || chunkIndex >= document.submissionChunkCount()) {
            throw new IllegalArgumentException(
                    "mail inspection submission chunk index is invalid");
        }
        HmacIdentifier hash = HmacIdentifier.fromProtectedValue(
                document.jobHash());
        List<String> keys = new ArrayList<>();
        keys.add(keyFactory.adminMailInspectionJobMetaKey(hash));
        keys.add(keyFactory.adminMailInspectionJobCountsKey(hash));
        keys.add(keyFactory.adminMailInspectionJobRevisionKey(hash));
        keys.addAll(allJobKeys(document));
        List<?> result = execute(
                UPDATE_SUBMISSION,
                keys,
                mode,
                Integer.toString(chunkIndex),
                Long.toString(now.toEpochMilli()),
                Long.toString(now.plus(
                        properties.submission().incompleteRetention())
                        .toEpochMilli()),
                Long.toString(activeExpiry(now).toEpochMilli()));
        boolean changed = changedOrThrow(result);
        if (changed) {
            publish(document, number(result.get(1)),
                    MailInspectionJobEventType.PROGRESS);
        }
        return changed;
    }

    private Optional<MailInspectionRedisJobDocument> findMetaByHash(
            String protectedHash) {
        HmacIdentifier hash;
        try {
            hash = HmacIdentifier.fromProtectedValue(protectedHash);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        try {
            List<Object> responses =
                    redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public Object execute(RedisOperations operations) {
                    operations.opsForHash().entries(
                            keyFactory.adminMailInspectionJobMetaKey(hash));
                    operations.opsForValue().get(
                            keyFactory.adminMailInspectionJobRevisionKey(
                                    hash));
                    return null;
                }
            });
            if (responses.size() < 2
                    || !(responses.getFirst() instanceof Map<?, ?> raw)
                    || raw.isEmpty()) {
                return Optional.empty();
            }
            Object revisionValue = responses.get(1);
            if (revisionValue == null) {
                throw unavailable(null);
            }
            MailInspectionRedisJobDocument resolved = document(
                    stringMap(raw),
                    number(revisionValue));
            // 元数据中的公开 ID、HMAC 与实际索引必须三方一致，防止损坏文档把读取引向另一任务的结果或计数 Key。
            if (!protectedHash.equals(resolved.jobHash())
                    || !protectedHash.equals(
                            keyHasher.hashJobId(resolved.jobId()).value())) {
                throw unavailable(null);
            }
            return Optional.of(resolved);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private MailInspectionRedisJobDocument requiredMeta(String jobId) {
        return findSnapshotMeta(jobId).orElseThrow(() -> new AdminException(
                AdminErrorCode.ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND,
                "mail inspection job not found"));
    }

    private MailInspectionRedisJobDocument requiredResolved(
            String hash, MailInspectionRedisJobDocument fallback) {
        return findMetaByHash(hash).orElse(fallback);
    }

    private Map<String, String> readCounts(
            MailInspectionRedisJobDocument document) {
        Map<String, String> counts = hashEntries(
                keyFactory.adminMailInspectionJobCountsKey(
                HmacIdentifier.fromProtectedValue(document.jobHash())));
        if (counts.isEmpty()) {
            throw unavailable(null);
        }
        return counts;
    }

    private List<MailInspectionResult> readResults(
            MailInspectionRedisJobDocument document,
            int lineExclusive,
            int limit) {
        int bucketSize = properties.job().resultBucketSize();
        int firstBucket = lineExclusive / bucketSize;
        int bucketCount = RedisAdminMailInspectionJobStoreSupport.bucketCount(
                document.requestedCount(),
                bucketSize);
        if (firstBucket >= bucketCount) {
            return List.of();
        }
        HmacIdentifier hash = HmacIdentifier.fromProtectedValue(
                document.jobHash());
        List<MailInspectionResult> results = new ArrayList<>();
        int bucketsPerBatch = Math.max(
                1,
                (properties.job().snapshotBatchSize() + bucketSize - 1)
                        / bucketSize);
        // 每次只读取约一个 SSE 批次对应的结果桶，避免完整一万行任务形成单个大 Pipeline 响应。
        for (int bucketOffset = firstBucket;
                bucketOffset < bucketCount && results.size() < limit;
                bucketOffset += bucketsPerBatch) {
            List<String> keys = new ArrayList<>(bucketsPerBatch);
            int bucketLimit = Math.min(
                    bucketCount,
                    bucketOffset + bucketsPerBatch);
            for (int bucket = bucketOffset; bucket < bucketLimit; bucket++) {
                keys.add(keyFactory.adminMailInspectionJobResultBucketKey(
                        hash,
                        bucket));
            }
            List<Object> responses;
            try {
                responses = redisTemplate.executePipelined(
                        new SessionCallback<>() {
                    @Override
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    public Object execute(RedisOperations operations) {
                        for (String key : keys) {
                            operations.opsForHash().entries(key);
                        }
                        return null;
                    }
                });
            } catch (RuntimeException exception) {
                throw unavailable(exception);
            }
            for (Object response : responses) {
                if (!(response instanceof Map<?, ?> bucket)) {
                    continue;
                }
                for (Map.Entry<?, ?> entry : bucket.entrySet()) {
                    int line = Integer.parseInt(text(entry.getKey()));
                    if (line > lineExclusive) {
                        results.add(codec.readResult(
                                text(entry.getValue())));
                    }
                }
            }
        }
        return results.stream()
                .sorted(Comparator.comparingInt(MailInspectionResult::lineNumber))
                .limit(limit)
                .toList();
    }

    private MailInspectionJobSnapshot snapshot(
            MailInspectionRedisJobDocument document,
            Map<String, String> counts,
            List<MailInspectionResult> results) {
        int processed = integer(counts, "processedCount");
        int running = integer(counts, "runningCount");
        int queued = integer(counts, "queuedCount");
        EnumMap<MailInspectionResultStatus, Integer> summary =
                new EnumMap<>(MailInspectionResultStatus.class);
        // 汇总计数来自 Redis Counts，而不是当前返回的结果页，确保恢复列表和分批快照不会把未加载结果误报为零。
        for (MailInspectionResultStatus status :
                MailInspectionResultStatus.values()) {
            int count = integer(counts, "status:" + status.name());
            if (count > 0) {
                summary.put(status, count);
            }
        }
        int remaining = Math.max(0, running + queued);
        int confirmed = integer(
                counts, "confirmedSubmissionChunkCount");
        int dispatched = integer(
                counts, "dispatchedSubmissionChunkCount");
        return new MailInspectionJobSnapshot(
                document.jobId(),
                document.revision(),
                document.inspectionType(),
                document.status(),
                document.requestedCount(),
                document.acceptedCount(),
                document.duplicateCount(),
                document.invalidCount(),
                processed,
                running,
                queued,
                document.recoveredAfterRestart(),
                document.resumeRequired(),
                document.resultHistoryLost(),
                document.lostResultCount(),
                remaining,
                remaining,
                document.businessConcurrency(),
                integer(counts, "dispatchFailedCount"),
                document.submissionChunkCount(),
                confirmed,
                dispatched,
                Math.max(0, document.submissionChunkCount() - confirmed),
                document.submissionExpiresAt(),
                document.recoveredAt(),
                document.pendingItems(),
                document.createdAt(),
                document.startedAt(),
                document.completedAt(),
                document.expiresAt(),
                new MailInspectionJobSummary(summary),
                results);
    }

    private MailInspectionRedisJobDocument document(
            Map<String, String> values, long revision) {
        return new MailInspectionRedisJobDocument(
                Integer.parseInt(required(values, "schemaVersion")),
                required(values, "jobId"),
                required(values, "jobHash"),
                MailInspectionType.valueOf(required(values, "inspectionType")),
                MailInspectionJobStatus.valueOf(required(values, "status")),
                integer(values, "requestedCount"),
                integer(values, "acceptedCount"),
                integer(values, "duplicateCount"),
                integer(values, "invalidCount"),
                integer(values, "businessConcurrency"),
                integer(values, "completionTarget"),
                emptyToNull(values.get("clientRequestId")),
                emptyToNull(values.get("requestFingerprint")),
                integer(values, "submissionChunkCount"),
                Boolean.parseBoolean(values.get("recoveredAfterRestart")),
                Boolean.parseBoolean(values.get("resultHistoryLost")),
                integer(values, "lostResultCount"),
                Boolean.parseBoolean(values.get("resumeRequired")),
                codec.readPendingItems(values.get("pendingItems")),
                instant(values.get("createdAt")),
                instant(values.get("startedAt")),
                instant(values.get("completedAt")),
                instant(values.get("expiresAt")),
                instant(values.get("submissionExpiresAt")),
                instant(values.get("recoveredAt")),
                revision);
    }

    private void writeKnownResults(
            MailInspectionRedisJobDocument document,
            List<MailInspectionResult> knownResults) {
        HmacIdentifier hash = HmacIdentifier.fromProtectedValue(
                document.jobHash());
        int batchSize = properties.job().snapshotBatchSize();
        // 恢复结果使用有界 Pipeline，避免一万个结果在单次请求中形成大命令；状态汇总已由恢复 Lua 原子写入，此处只补齐幂等结果文档。
        for (int offset = 0; offset < knownResults.size(); offset += batchSize) {
            List<MailInspectionResult> batch = knownResults.subList(
                    offset,
                    Math.min(knownResults.size(), offset + batchSize));
            try {
                redisTemplate.executePipelined(new SessionCallback<>() {
                    @Override
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    public Object execute(RedisOperations operations) {
                        for (MailInspectionResult result : batch) {
                            int bucket =
                                    RedisAdminMailInspectionJobStoreSupport
                                            .bucketForLine(
                                                    result.lineNumber(),
                                                    properties.job()
                                                            .resultBucketSize());
                            String key =
                                    keyFactory
                                            .adminMailInspectionJobResultBucketKey(
                                                    hash,
                                                    bucket);
                            operations.opsForHash().put(
                                    key,
                                    Integer.toString(result.lineNumber()),
                                    codec.writeResult(result));
                            operations.expireAt(
                                    key,
                                    java.util.Date.from(document.expiresAt()));
                        }
                        return null;
                    }
                });
            } catch (RuntimeException exception) {
                throw unavailable(exception);
            }
        }
    }

    private List<MailInspectionResult> validateKnownResults(
            MailInspectionRedisJobDocument document,
            List<MailInspectionResult> knownResults) {
        List<MailInspectionResult> safeResults = knownResults == null
                ? List.of()
                : List.copyOf(knownResults);
        if (safeResults.size() > document.completionTarget()
                || safeResults.size() > properties.job().maxCredentialLines()) {
            throw new IllegalArgumentException(
                    "mail inspection recovery result count is invalid");
        }
        Set<Integer> lineNumbers = new java.util.HashSet<>();
        for (MailInspectionResult result : safeResults) {
            validateLineNumber(document, result.lineNumber());
            if (!lineNumbers.add(result.lineNumber())) {
                throw new IllegalArgumentException(
                        "mail inspection recovery contains duplicate result lines");
            }
        }
        return safeResults;
    }

    private List<String> allJobKeys(
            MailInspectionRedisJobDocument document) {
        HmacIdentifier hash = HmacIdentifier.fromProtectedValue(
                document.jobHash());
        List<String> keys = new ArrayList<>();
        keys.add(keyFactory.adminMailInspectionJobMetaKey(hash));
        keys.add(keyFactory.adminMailInspectionJobCountsKey(hash));
        keys.add(keyFactory.adminMailInspectionJobRevisionKey(hash));
        keys.add(keyFactory.adminMailInspectionJobActiveKey(
                typeSegment(document.inspectionType())));
        if (document.clientRequestId() != null) {
            keys.add(keyFactory.adminMailInspectionJobIdempotencyKey(
                    keyHasher.hashClientRequestId(
                            document.clientRequestId())));
        }
        keys.addAll(bucketKeys(document));
        return keys;
    }

    private List<String> bucketKeys(
            MailInspectionRedisJobDocument document) {
        HmacIdentifier hash = HmacIdentifier.fromProtectedValue(
                document.jobHash());
        int count = RedisAdminMailInspectionJobStoreSupport.bucketCount(
                document.requestedCount(),
                properties.job().resultBucketSize());
        List<String> keys = new ArrayList<>(count);
        for (int bucket = 0; bucket < count; bucket++) {
            keys.add(keyFactory.adminMailInspectionJobResultBucketKey(
                    hash, bucket));
        }
        return keys;
    }

    private List<String> activeKeys() {
        return java.util.Arrays.stream(MailInspectionType.values())
                .map(type -> keyFactory.adminMailInspectionJobActiveKey(
                        typeSegment(type)))
                .toList();
    }

    private String acceptanceKey(MailInspectionType type) {
        return keyFactory.adminMailInspectionJobAcceptanceKey(
                typeSegment(type));
    }

    private Instant activeExpiry(Instant now) {
        return now.plus(properties.job().activeLease());
    }

    private Instant terminalExpiry(Instant now) {
        return now.plus(properties.job().terminalRetention());
    }

    private void publish(
            MailInspectionRedisJobDocument document,
            long revision,
            MailInspectionJobEventType eventType) {
        eventPublisher.publish(new MailInspectionJobEvent(
                MailInspectionJobEvent.SCHEMA_VERSION,
                document.jobHash(),
                revision,
                eventType,
                document.inspectionType(),
                clock.instant()));
    }

    private Map<String, String> hashEntries(String key) {
        try {
            return stringMap(redisTemplate.opsForHash().entries(key));
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private List<MailInspectionRedisJobDocument> loadActiveDocuments() {
        try {
            // 活动索引先用一次 MGET 读取，再用一个 Pipeline 成批读取元数据与 revision。
            List<String> protectedHashes =
                    redisTemplate.opsForValue().multiGet(activeKeys());
            if (protectedHashes == null) {
                throw unavailable(null);
            }
            List<String> hashes = protectedHashes.stream()
                    .filter(Objects::nonNull)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
            if (hashes.isEmpty()) {
                return List.of();
            }
            List<Object> responses =
                    redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public Object execute(RedisOperations operations) {
                    for (String protectedHash : hashes) {
                        HmacIdentifier hash =
                                HmacIdentifier.fromProtectedValue(
                                        protectedHash);
                        operations.opsForHash().entries(
                                keyFactory.adminMailInspectionJobMetaKey(
                                        hash));
                        operations.opsForValue().get(
                                keyFactory
                                        .adminMailInspectionJobRevisionKey(
                                                hash));
                    }
                    return null;
                }
            });
            List<MailInspectionRedisJobDocument> documents =
                    new ArrayList<>();
            for (int index = 0; index < hashes.size(); index++) {
                Object rawMeta = responses.get(index * 2);
                Object rawRevision = responses.get(index * 2 + 1);
                if (!(rawMeta instanceof Map<?, ?> values)
                        || values.isEmpty()
                        || rawRevision == null) {
                    throw unavailable(null);
                }
                MailInspectionRedisJobDocument document = document(
                        stringMap(values),
                        number(rawRevision));
                String protectedHash = hashes.get(index);
                if (!protectedHash.equals(document.jobHash())
                        || !protectedHash.equals(
                                keyHasher.hashJobId(
                                        document.jobId()).value())) {
                    throw unavailable(null);
                }
                if (document.active()) {
                    documents.add(document);
                }
            }
            return List.copyOf(documents);
        } catch (AdminException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private static Map<String, String> stringMap(Map<?, ?> raw) {
        Map<String, String> values = new HashMap<>();
        raw.forEach((field, value) ->
                values.put(text(field), text(value)));
        return Map.copyOf(values);
    }

    private <T> T execute(
            RedisScript<T> script, List<String> keys, Object... args) {
        try {
            T result = redisTemplate.execute(script, keys, args);
            if (result == null) {
                throw unavailable(null);
            }
            return result;
        } catch (AdminException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private static int status(List<?> values) {
        if (values == null || values.isEmpty()) {
            throw unavailable(null);
        }
        return Math.toIntExact(number(values.getFirst()));
    }

    private static boolean changedOrThrow(List<?> values) {
        int result = status(values);
        if (result < 0) {
            throw new AdminException(
                    AdminErrorCode.ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND,
                    "mail inspection job expired during atomic update");
        }
        return result == 1;
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(text(value));
    }

    private static int integer(Map<String, String> values, String key) {
        String value = values.get(key);
        return value == null || value.isBlank()
                ? 0
                : Integer.parseInt(value);
    }

    private static void validateLineNumber(
            MailInspectionRedisJobDocument document,
            int lineNumber) {
        if (lineNumber < 1 || lineNumber > document.requestedCount()) {
            throw new IllegalArgumentException(
                    "mail inspection line number exceeds job boundary");
        }
    }

    private static String required(
            Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "mail inspection Redis document field is missing: " + key);
        }
        return value;
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank()
                ? null
                : Instant.ofEpochMilli(Long.parseLong(value));
    }

    private static String epoch(Instant value) {
        return value == null ? "" : Long.toString(value.toEpochMilli());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String text(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (value == null) {
            throw new IllegalStateException(
                    "mail inspection Redis response is missing");
        }
        return value.toString();
    }

    private static String typeSegment(MailInspectionType type) {
        return type.name()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
    }

    private static AdminException unavailable(Throwable cause) {
        return cause == null
                ? new AdminException(
                        AdminErrorCode.ADMIN_MAIL_INSPECTION_UNAVAILABLE,
                        "mail inspection Redis is unavailable")
                : new AdminException(
                        AdminErrorCode.ADMIN_MAIL_INSPECTION_UNAVAILABLE,
                        "mail inspection Redis is unavailable",
                        cause);
    }

    private static RedisScript<String> stringScript(String name) {
        return script(name, String.class);
    }

    private static RedisScript<Long> longScript(String name) {
        return script(name, Long.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RedisScript<List> listScript(String name) {
        return (RedisScript) script(name, List.class);
    }

    private static <T> RedisScript<T> script(String name, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(
                "lua/admin-mail-inspection/" + name));
        script.setResultType(type);
        return script;
    }
}
