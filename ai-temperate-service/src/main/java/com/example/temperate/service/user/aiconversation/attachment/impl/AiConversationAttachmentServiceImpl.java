package com.example.temperate.service.user.aiconversation.attachment.impl;

import cn.hutool.core.lang.id.NanoId;
import com.aliyun.sdk.service.oss2.progress.ProgressListener;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentCategory;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentFinalization;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentObjectKeyFactory;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentObjectStorage;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentProgressObjectStorage;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentService;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentUploadReference;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationGeneratedMedia;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationGeneratedUploadResult;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationGeneratedUploadProgressAwareSession;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationGeneratedUploadProgressListener;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationGeneratedUploadSession;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationPreupload;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationPreuploadBatch;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationPreuploadFile;
import com.example.temperate.service.user.aiconversation.attachment.config.AiConversationAttachmentProperties;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.generation.progress.AiConversationMediaType;
import com.example.temperate.service.user.aiconversation.generation.progress.AiConversationMediaUploadProgress;
import com.example.temperate.service.user.aiconversation.generation.progress.AiConversationMediaUploadProgressThrottle;
import com.example.temperate.service.user.aiconversation.generation.progress.AiConversationMediaUploadState;
import io.micrometer.core.instrument.Metrics;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 实现任意文件的会话附件生命周期，所有 Object Key 均由服务端重建且单个落盘操作执行有限重试。
 */
@Service
public final class AiConversationAttachmentServiceImpl
        implements AiConversationAttachmentService {

    private static final int MAXIMUM_GENERATED_IMAGE_OUTPUTS = 10;

    private static final Logger log =
            LoggerFactory.getLogger(AiConversationAttachmentServiceImpl.class);
    private static final int ATTACHMENT_ID_LENGTH = 38;
    private static final Pattern CONTENT_TYPE = Pattern.compile(
            "^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+$");
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
            "txt", "md", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "csv", "json", "xml", "yaml", "yml", "rtf", "odt", "ods", "odp");
    private static final Set<String> ARCHIVE_EXTENSIONS = Set.of(
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz");

    private final AiConversationAttachmentObjectStorage storage;
    private final AiConversationAttachmentObjectKeyFactory keyFactory;
    private final AiConversationAttachmentProperties properties;
    private final SecureRandom secureRandom;
    private final Clock clock;
    private final Executor finalizationExecutor;

    public AiConversationAttachmentServiceImpl(
            AiConversationAttachmentObjectStorage storage,
            AiConversationAttachmentObjectKeyFactory keyFactory,
            AiConversationAttachmentProperties properties,
            Clock clock) {
        this(storage, keyFactory, properties, clock, Runnable::run);
    }

    @Autowired
    public AiConversationAttachmentServiceImpl(
            AiConversationAttachmentObjectStorage storage,
            AiConversationAttachmentObjectKeyFactory keyFactory,
            AiConversationAttachmentProperties properties,
            Clock clock,
            @Qualifier("aiConversationAttachmentFinalizationExecutor")
            Executor finalizationExecutor) {
        this.storage = Objects.requireNonNull(storage);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        this.finalizationExecutor = Objects.requireNonNull(finalizationExecutor);
        this.secureRandom = new SecureRandom();
    }

    @Override
    public AiConversationPreuploadBatch createPreuploads(
            long userId,
            String userPublicId,
            List<AiConversationPreuploadFile> files) {
        if (userId <= 0L) {
            throw invalid("当前用户身份无效。");
        }
        List<AiConversationPreuploadFile> safeFiles = validateBatch(files);
        String uploadSessionId = newUploadSessionId();
        List<AiConversationPreupload> results = new ArrayList<>(safeFiles.size());
        for (AiConversationPreuploadFile file : safeFiles) {
            String attachmentId = NanoId.randomNanoId(ATTACHMENT_ID_LENGTH);
            String fileName = keyFactory.sanitizeFileName(file.fileName());
            String contentType = normalizeContentType(file.contentType());
            String objectKey = keyFactory.temporaryKey(
                    userPublicId,
                    uploadSessionId,
                    attachmentId,
                    fileName);
            try {
                var signed = storage.generatePresignedPut(
                        objectKey,
                        contentType,
                        file.sizeBytes());
                if (!signed.expiresAt().isAfter(clock.instant())) {
                    throw new IllegalStateException("OSS returned an expired pre-signed URL");
                }
                results.add(new AiConversationPreupload(
                        attachmentId,
                        fileName,
                        contentType,
                        Long.toString(file.sizeBytes()),
                        signed.uploadUrl(),
                        signed.method(),
                        signed.headers(),
                        signed.expiresAt()));
            } catch (RuntimeException exception) {
                throw storageUnavailable("暂时无法创建附件上传地址。", exception);
            }
        }
        return new AiConversationPreuploadBatch(uploadSessionId, results);
    }

    @Override
    public List<AiConversationAttachment> validateTemporaryInputs(
            String userPublicId,
            List<AiConversationAttachmentUploadReference> references) {
        List<AiConversationAttachmentUploadReference> safe =
                references == null ? List.of() : List.copyOf(references);
        validateReferenceTotals(safe);
        List<AiConversationAttachment> attachments = new ArrayList<>(safe.size());
        for (AiConversationAttachmentUploadReference reference : safe) {
            String fileName = keyFactory.sanitizeFileName(reference.fileName());
            String contentType = normalizeContentType(reference.contentType());
            String objectKey;
            try {
                objectKey = keyFactory.temporaryKey(
                        userPublicId,
                        reference.uploadSessionId(),
                        reference.attachmentId(),
                        fileName);
            } catch (IllegalArgumentException exception) {
                throw invalid("附件预上传标识无效。");
            }
            AiConversationAttachmentObjectStorage.ObjectMetadata metadata;
            try {
                metadata = storage.headObject(objectKey);
            } catch (AiConversationAttachmentObjectStorage.ObjectNotFoundException exception) {
                throw invalid("附件尚未上传、已过期或不属于当前用户。");
            } catch (RuntimeException exception) {
                throw storageUnavailable("暂时无法校验附件。", exception);
            }
            if (metadata.sizeBytes() != reference.sizeBytes()
                    || !sameContentType(contentType, metadata.contentType())) {
                throw invalid("附件实际大小或 Content-Type 与预上传声明不一致。");
            }
            attachments.add(AiConversationAttachment.available(
                    reference.attachmentId(),
                    fileName,
                    contentType,
                    reference.sizeBytes(),
                    classify(fileName, contentType),
                    keyFactory.temporaryLocator(objectKey)));
        }
        return List.copyOf(attachments);
    }

    @Override
    public String resolveModelUrl(AiConversationAttachment attachment) {
        if (attachment == null
                || attachment.state()
                        != com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentState.AVAILABLE) {
            throw invalid("附件不可供模型读取。");
        }
        if (attachment.url().startsWith("https://")) {
            return attachment.url();
        }
        try {
            String objectKey = keyFactory.objectKeyFromTemporaryLocator(attachment.url());
            return storage.generatePresignedGet(objectKey).downloadUrl();
        } catch (AiConversationAttachmentObjectStorage.ObjectNotFoundException exception) {
            throw invalid("临时附件已过期。");
        } catch (RuntimeException exception) {
            throw storageUnavailable("暂时无法读取附件。", exception);
        }
    }

    @Override
    public AiConversationAttachmentFinalization finalizeAttachments(
            String userPublicId,
            String conversationPublicId,
            String messagePublicId,
            List<AiConversationAttachment> inputAttachments,
            List<AiConversationGeneratedMedia> generatedMedia) {
        return finalizeAttachments(
                userPublicId,
                conversationPublicId,
                messagePublicId,
                inputAttachments,
                generatedMedia,
                defaultFinalizationTimeout());
    }

    @Override
    public AiConversationAttachmentFinalization finalizeAttachments(
            String userPublicId,
            String conversationPublicId,
            String messagePublicId,
            List<AiConversationAttachment> inputAttachments,
            List<AiConversationGeneratedMedia> generatedMedia,
            Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw storageUnavailable(
                    "附件最终化可用时间不足。",
                    new IllegalStateException("Attachment finalization deadline expired"));
        }
        GeneratedUploadSession uploadSession = new GeneratedUploadSession(
                userPublicId, conversationPublicId, messagePublicId);
        List<String> createdKeys = new ArrayList<>();
        try {
            List<AiConversationAttachment> finalizedInput = new ArrayList<>();
            boolean partial = false;
            for (AiConversationAttachment attachment : safe(inputAttachments)) {
                String finalKey = keyFactory.finalKey(
                        userPublicId,
                        conversationPublicId,
                        messagePublicId,
                        attachment.attachmentId(),
                        attachment.fileName());
                try {
                    String sourceKey = keyFactory.objectKeyFromTemporaryLocator(attachment.url());
                    String publicUrl = retry(ignored -> storage.copyToPublic(
                            sourceKey,
                            finalKey,
                            attachment.contentType()));
                    createdKeys.add(finalKey);
                    finalizedInput.add(AiConversationAttachment.available(
                            attachment.attachmentId(),
                            attachment.fileName(),
                            attachment.contentType(),
                            attachment.sizeBytesAsLong(),
                            attachment.category(),
                            publicUrl));
                } catch (RuntimeException exception) {
                    partial = true;
                    finalizedInput.add(AiConversationAttachment.storageFailed(
                            attachment.attachmentId(),
                            attachment.fileName(),
                            attachment.contentType(),
                            attachment.sizeBytesAsLong(),
                            attachment.category()));
                    recordStorageFailure("input");
                }
            }
            List<AiConversationGeneratedMedia> safeGeneratedMedia = safeMedia(generatedMedia);
            int submitted = Math.min(
                    safeGeneratedMedia.size(), MAXIMUM_GENERATED_IMAGE_OUTPUTS);
            for (int index = 0; index < submitted; index++) {
                uploadSession.submit((short) index, safeGeneratedMedia.get(index));
            }
            List<AiConversationGeneratedUploadResult> generatedResults =
                    uploadSession.finish(timeout);
            List<AiConversationAttachment> finalizedResponse =
                    new ArrayList<>(safeGeneratedMedia.size());
            for (AiConversationGeneratedUploadResult result : generatedResults) {
                finalizedResponse.add(result.attachment());
                if (result.createdObjectKey() != null) {
                    createdKeys.add(result.createdObjectKey());
                }
                partial |= !result.successful();
            }
            for (int index = submitted; index < safeGeneratedMedia.size(); index++) {
                finalizedResponse.add(failedGeneratedAttachment(
                        safeGeneratedMedia.get(index)));
                partial = true;
            }
            // 兼容 API 把补偿责任连同 createdObjectKeys 交给既有调用方；这不是数据库终态后的 Session commit。
            uploadSession.handoffCompensationToCaller();
            return new AiConversationAttachmentFinalization(
                    finalizedInput,
                    finalizedResponse,
                    createdKeys,
                    partial);
        } catch (RuntimeException failure) {
            uploadSession.abortAndCompensate();
            compensateCreatedObjects(createdKeys);
            throw failure;
        }
    }

    @Override
    public AiConversationGeneratedUploadSession openGeneratedUploadSession(
            String userPublicId,
            String conversationPublicId,
            String messagePublicId) {
        return new GeneratedUploadSession(
                userPublicId, conversationPublicId, messagePublicId);
    }

    private AiConversationGeneratedUploadResult uploadGenerated(
            GeneratedUploadPlan plan,
            GeneratedUploadSession session) {
        GeneratedUploadProgressReporter progressReporter = session.progressReporter(plan);
        if (!plan.valid()) {
            recordStorageFailure("generated");
            progressReporter.failed();
            return failedGeneratedResult(plan);
        }
        try {
            String publicUrl = retry(attempt -> {
                progressReporter.started(attempt);
                if (storage instanceof AiConversationAttachmentProgressObjectStorage progressStorage) {
                    return progressStorage.putPublic(
                            plan.finalKey(),
                            plan.bytes(),
                            plan.contentType(),
                            progressReporter.progressListener());
                }
                return storage.putPublic(
                        plan.finalKey(),
                        plan.bytes(),
                        plan.contentType());
            });
            // Session 进入中止态后不再接纳对象；晚到的 OSS 成功必须立即补偿，避免留下无人引用对象。
            if (!session.acceptCreated(plan.finalKey())) {
                compensateCreatedObjects(List.of(plan.finalKey()));
                progressReporter.failed();
                return failedGeneratedResult(plan);
            }
            progressReporter.completed();
            return new AiConversationGeneratedUploadResult(
                    plan.outputIndex(),
                    AiConversationAttachment.available(
                            plan.attachmentId(),
                            plan.fileName(),
                            plan.contentType(),
                            plan.bytes().length,
                            plan.category(),
                            publicUrl),
                    plan.finalKey());
        } catch (RuntimeException exception) {
            recordStorageFailure("generated");
            progressReporter.failed();
            return failedGeneratedResult(plan);
        }
    }

    private AiConversationGeneratedUploadResult failedGeneratedResult(
            GeneratedUploadPlan plan) {
        return new AiConversationGeneratedUploadResult(
                plan.outputIndex(),
                AiConversationAttachment.storageFailed(
                        plan.attachmentId(),
                        plan.fileName(),
                        plan.contentType(),
                        plan.bytes().length,
                        plan.category()),
                null);
    }

    private AiConversationAttachment failedGeneratedAttachment(
            AiConversationGeneratedMedia media) {
        String fileName = keyFactory.sanitizeFileName(media.fileName());
        String contentType = normalizeContentType(media.contentType());
        byte[] bytes = media.bytes();
        return AiConversationAttachment.storageFailed(
                NanoId.randomNanoId(ATTACHMENT_ID_LENGTH),
                fileName,
                contentType,
                bytes.length,
                classify(fileName, contentType));
    }

    private Duration defaultFinalizationTimeout() {
        Duration attempt = properties.uploadConnectTimeout()
                .plus(properties.uploadReadWriteTimeout());
        long waves = (MAXIMUM_GENERATED_IMAGE_OUTPUTS + 2L) / 3L;
        return attempt.multipliedBy((long) properties.finalizationAttempts() * waves)
                .plusSeconds(5L);
    }

    private static long deadlineAfter(Duration timeout) {
        long now = System.nanoTime();
        try {
            return Math.addExact(now, timeout.toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    @Override
    public void compensateCreatedObjects(List<String> objectKeys) {
        for (String objectKey : objectKeys == null ? List.<String>of() : objectKeys) {
            try {
                storage.deleteObject(objectKey);
            } catch (RuntimeException exception) {
                Metrics.counter(
                                "ai.conversation.attachment.cleanup",
                                "outcome",
                                "failed")
                        .increment();
                log.warn(
                        "Conversation attachment compensation failed, errorType={}",
                        exception.getClass().getName());
            }
        }
    }

    private List<AiConversationPreuploadFile> validateBatch(
            List<AiConversationPreuploadFile> files) {
        List<AiConversationPreuploadFile> safe = files == null ? List.of() : List.copyOf(files);
        if (safe.isEmpty() || safe.size() > properties.maxFilesPerMessage()) {
            throw invalid("单条消息必须包含 1 至 8 个附件。");
        }
        long total = 0L;
        for (AiConversationPreuploadFile file : safe) {
            if (file == null
                    || file.sizeBytes() <= 0L
                    || file.sizeBytes() > properties.maxFileBytes()) {
                throw invalid("单个附件必须大于 0 且不超过 100 MB。");
            }
            keyFactory.sanitizeFileName(file.fileName());
            normalizeContentType(file.contentType());
            total = Math.addExact(total, file.sizeBytes());
        }
        if (total > properties.maxTotalBytesPerMessage()) {
            throw invalid("单条消息附件总大小不得超过 200 MB。");
        }
        return safe;
    }

    private void validateReferenceTotals(
            List<AiConversationAttachmentUploadReference> references) {
        if (references.size() > properties.maxFilesPerMessage()) {
            throw invalid("单条消息附件数量不得超过 8 个。");
        }
        long total = 0L;
        for (AiConversationAttachmentUploadReference reference : references) {
            if (reference == null
                    || reference.sizeBytes() <= 0L
                    || reference.sizeBytes() > properties.maxFileBytes()) {
                throw invalid("附件声明大小无效。");
            }
            total = Math.addExact(total, reference.sizeBytes());
        }
        if (total > properties.maxTotalBytesPerMessage()) {
            throw invalid("单条消息附件总大小不得超过 200 MB。");
        }
    }

    private String retry(StorageAction action) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= properties.finalizationAttempts(); attempt++) {
            try {
                return action.run(attempt);
            } catch (RuntimeException exception) {
                last = exception;
            }
        }
        throw Objects.requireNonNull(last);
    }

    private String newUploadSessionId() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "application/octet-stream";
        }
        String value = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (value.length() > 255 || !CONTENT_TYPE.matcher(value).matches()) {
            throw invalid("附件 Content-Type 无效。");
        }
        return value;
    }

    private static boolean sameContentType(String expected, String actual) {
        try {
            return expected.equals(normalizeContentType(actual));
        } catch (AiConversationException exception) {
            return false;
        }
    }

    private AiConversationAttachmentCategory classify(String fileName, String contentType) {
        if (contentType.startsWith("image/")) {
            return AiConversationAttachmentCategory.IMAGE;
        }
        if (contentType.startsWith("audio/")) {
            return AiConversationAttachmentCategory.AUDIO;
        }
        if (contentType.startsWith("video/")) {
            return AiConversationAttachmentCategory.VIDEO;
        }
        String extension = keyFactory.safeExtension(fileName);
        if (ARCHIVE_EXTENSIONS.contains(extension)) {
            return AiConversationAttachmentCategory.ARCHIVE;
        }
        if (DOCUMENT_EXTENSIONS.contains(extension)
                || contentType.startsWith("text/")
                || contentType.contains("pdf")
                || contentType.contains("document")
                || contentType.contains("spreadsheet")
                || contentType.contains("presentation")) {
            return AiConversationAttachmentCategory.DOCUMENT;
        }
        return AiConversationAttachmentCategory.OTHER;
    }

    private void recordStorageFailure(String operation) {
        Metrics.counter(
                        "ai.conversation.attachment.persist",
                        "outcome",
                        "failed",
                        "operation",
                        operation)
                .increment();
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static List<AiConversationGeneratedMedia> safeMedia(
            List<AiConversationGeneratedMedia> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static AiConversationException invalid(String message) {
        return new AiConversationException(
                AiConversationErrorCode.AI_ATTACHMENT_INVALID,
                message,
                false);
    }

    private static AiConversationException storageUnavailable(
            String message,
            RuntimeException exception) {
        return new AiConversationException(
                AiConversationErrorCode.AI_ATTACHMENT_STORAGE_UNAVAILABLE,
                message,
                true);
    }

    /**
     * 保存单个生成媒体在进入并发执行器前已经确定的路径、格式和边界校验结果。
     */
    private record GeneratedUploadPlan(
            short outputIndex,
            String attachmentId,
            String fileName,
            String contentType,
            byte[] bytes,
            AiConversationAttachmentCategory category,
            String finalKey,
            boolean valid) {
    }

    /**
     * 把一次 Generation 的槽位提交、共同截止时间和晚到 OSS 成功纳入同一补偿边界。
     */
    private final class GeneratedUploadSession
            implements AiConversationGeneratedUploadProgressAwareSession {

        private final String userPublicId;
        private final String conversationPublicId;
        private final String messagePublicId;
        private final Map<Short, CompletableFuture<AiConversationGeneratedUploadResult>>
                futures = new TreeMap<>();
        private final List<String> acceptedKeys = new ArrayList<>();
        private GeneratedUploadSessionState state = GeneratedUploadSessionState.OPEN;
        private volatile AiConversationGeneratedUploadProgressListener progressListener =
                AiConversationGeneratedUploadProgressListener.noOp();
        private long reservedBytes;

        private GeneratedUploadSession(
                String userPublicId,
                String conversationPublicId,
                String messagePublicId) {
            this.userPublicId = Objects.requireNonNull(userPublicId);
            this.conversationPublicId = Objects.requireNonNull(conversationPublicId);
            this.messagePublicId = Objects.requireNonNull(messagePublicId);
        }

        @Override
        public synchronized void setProgressListener(
                AiConversationGeneratedUploadProgressListener progressListener) {
            if (state != GeneratedUploadSessionState.OPEN || !futures.isEmpty()) {
                throw new IllegalStateException(
                        "Generated upload progress listener must be bound before submission.");
            }
            this.progressListener = Objects.requireNonNull(progressListener);
        }

        private GeneratedUploadProgressReporter progressReporter(GeneratedUploadPlan plan) {
            return new GeneratedUploadProgressReporter(
                    plan.outputIndex(),
                    plan.bytes().length,
                    properties.finalizationAttempts(),
                    progressListener);
        }

        @Override
        public synchronized CompletableFuture<AiConversationGeneratedUploadResult> submit(
                short outputIndex,
                AiConversationGeneratedMedia media) {
            if (state != GeneratedUploadSessionState.OPEN) {
                throw new IllegalStateException("Generated upload session is not open.");
            }
            if (outputIndex < 0 || outputIndex >= MAXIMUM_GENERATED_IMAGE_OUTPUTS) {
                throw new IllegalArgumentException("Image output index is out of range.");
            }
            if (futures.containsKey(outputIndex)) {
                throw new IllegalStateException("Generated upload output index is duplicated.");
            }
            AiConversationGeneratedMedia safeMedia = Objects.requireNonNull(media);
            String attachmentId = NanoId.randomNanoId(ATTACHMENT_ID_LENGTH);
            String fileName = keyFactory.sanitizeFileName(safeMedia.fileName());
            String contentType = normalizeContentType(safeMedia.contentType());
            byte[] bytes = safeMedia.bytes();
            boolean withinTotal = bytes.length <= properties.maxTotalBytesPerMessage()
                    - reservedBytes;
            boolean valid = bytes.length > 0
                    && bytes.length <= properties.maxFileBytes()
                    && withinTotal;
            if (valid) {
                reservedBytes += bytes.length;
            }
            String finalKey = keyFactory.finalKey(
                    userPublicId,
                    conversationPublicId,
                    messagePublicId,
                    attachmentId,
                    fileName);
            GeneratedUploadPlan plan = new GeneratedUploadPlan(
                    outputIndex,
                    attachmentId,
                    fileName,
                    contentType,
                    bytes,
                    classify(fileName, contentType),
                    finalKey,
                    valid);
            if (!valid) {
                // 边界超限是单槽位可预期失败，不应占用 OSS 线程，也不能因全局队列繁忙升级为整批故障。
                recordStorageFailure("generated");
                CompletableFuture<AiConversationGeneratedUploadResult> failed =
                        CompletableFuture.completedFuture(failedGeneratedResult(plan));
                futures.put(outputIndex, failed);
                return failed;
            }
            try {
                // supplyAsync 只向单实例有界池提交任务；此处不等待 OSS，允许兄弟图片继续生成。
                CompletableFuture<AiConversationGeneratedUploadResult> future =
                        CompletableFuture.supplyAsync(
                                () -> uploadGenerated(plan, this),
                                finalizationExecutor);
                futures.put(outputIndex, future);
                return future;
            } catch (RuntimeException rejected) {
                if (valid) {
                    reservedBytes -= bytes.length;
                }
                throw storageUnavailable(
                        "附件最终化队列繁忙。",
                        new IllegalStateException(
                                "Attachment finalization executor rejected task",
                                rejected));
            }
        }

        @Override
        public List<AiConversationGeneratedUploadResult> finish(Duration timeout) {
            Map<Short, CompletableFuture<AiConversationGeneratedUploadResult>> snapshot;
            synchronized (this) {
                if (state != GeneratedUploadSessionState.OPEN) {
                    throw new IllegalStateException(
                            "Generated upload session cannot be finished twice.");
                }
                if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                    throw storageUnavailable(
                            "附件最终化可用时间不足。",
                            new IllegalStateException(
                                    "Attachment finalization deadline expired"));
                }
                state = GeneratedUploadSessionState.SEALED;
                snapshot = new TreeMap<>(futures);
            }
            long deadline = deadlineAfter(timeout);
            List<AiConversationGeneratedUploadResult> results =
                    new ArrayList<>(snapshot.size());
            try {
                for (CompletableFuture<AiConversationGeneratedUploadResult> future
                        : snapshot.values()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        throw new TimeoutException(
                                "Attachment finalization deadline expired");
                    }
                    results.add(future.get(remaining, TimeUnit.NANOSECONDS));
                }
                return List.copyOf(results);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                abortAndCompensate();
                throw storageUnavailable(
                        "附件最终化被中断。",
                        new IllegalStateException(
                                "Attachment finalization interrupted", exception));
            } catch (TimeoutException exception) {
                abortAndCompensate();
                throw storageUnavailable(
                        "附件最终化超时。",
                        new IllegalStateException(
                                "Attachment finalization timed out", exception));
            } catch (ExecutionException exception) {
                abortAndCompensate();
                throw storageUnavailable(
                        "附件最终化执行失败。",
                        new IllegalStateException(
                                "Attachment finalization task failed", exception));
            }
        }

        @Override
        public synchronized void commit() {
            if (state == GeneratedUploadSessionState.COMMITTED) {
                return;
            }
            if (state != GeneratedUploadSessionState.SEALED) {
                throw new IllegalStateException(
                        "Generated upload session is not ready to commit.");
            }
            state = GeneratedUploadSessionState.COMMITTED;
            acceptedKeys.clear();
        }

        @Override
        public void abortAndCompensate() {
            List<CompletableFuture<AiConversationGeneratedUploadResult>> pending;
            List<String> cleanup;
            synchronized (this) {
                if (state == GeneratedUploadSessionState.ABORTED
                        || state == GeneratedUploadSessionState.COMMITTED) {
                    return;
                }
                state = GeneratedUploadSessionState.ABORTED;
                pending = List.copyOf(futures.values());
                cleanup = List.copyOf(acceptedKeys);
                acceptedKeys.clear();
            }
            pending.forEach(future -> future.cancel(true));
            compensateCreatedObjects(cleanup);
        }

        private synchronized boolean acceptCreated(String objectKey) {
            if (state == GeneratedUploadSessionState.ABORTED
                    || state == GeneratedUploadSessionState.COMMITTED) {
                return false;
            }
            acceptedKeys.add(objectKey);
            return true;
        }

        private synchronized void handoffCompensationToCaller() {
            if (state != GeneratedUploadSessionState.SEALED) {
                throw new IllegalStateException(
                        "Generated upload session is not ready for handoff.");
            }
            state = GeneratedUploadSessionState.COMMITTED;
            acceptedKeys.clear();
        }
    }

    /**
     * 区分仍可接收槽位、等待终态、已经交权和必须补偿四个互斥阶段。
     */
    private enum GeneratedUploadSessionState {
        OPEN,
        SEALED,
        COMMITTED,
        ABORTED
    }

    @FunctionalInterface
    private interface StorageAction {
        String run(int attempt);
    }

    /**
     * 把 OSS SDK 的单次请求回调转换成图片槽位进度；回调发布失败不能反向中断已经开始的对象上传。
     */
    private static final class GeneratedUploadProgressReporter {

        private final short outputIndex;
        private final long totalBytes;
        private final int maxAttempts;
        private final AiConversationGeneratedUploadProgressListener listener;
        private final AtomicLong sequence = new AtomicLong();
        private int attempt = 1;
        private long transferredBytes;
        private boolean verifying;
        private AiConversationMediaUploadProgressThrottle throttle =
                new AiConversationMediaUploadProgressThrottle(System::currentTimeMillis);

        private GeneratedUploadProgressReporter(
                short outputIndex,
                long totalBytes,
                int maxAttempts,
                AiConversationGeneratedUploadProgressListener listener) {
            this.outputIndex = outputIndex;
            this.totalBytes = totalBytes;
            this.maxAttempts = maxAttempts;
            this.listener = listener;
        }

        private void started(int attempt) {
            this.attempt = attempt;
            this.transferredBytes = 0L;
            this.verifying = false;
            // 每次实际重传都从零开始展示，不能把上一次失败的字节误当作本次已传输。
            this.throttle = new AiConversationMediaUploadProgressThrottle(System::currentTimeMillis);
            publish(AiConversationMediaUploadState.UPLOADING, 0L, 0, null);
        }

        private ProgressListener progressListener() {
            return new ProgressListener() {
                @Override
                public void onProgress(long increment, long transferred, long total) {
                    long safeTransferred = Math.min(totalBytes, Math.max(0L, transferred));
                    transferredBytes = Math.max(transferredBytes, safeTransferred);
                    publish(
                            AiConversationMediaUploadState.UPLOADING,
                            transferredBytes,
                            percentage(transferredBytes),
                            null);
                }

                @Override
                public void onFinish() {
                    verify();
                }
            };
        }

        private void completed() {
            if (!verifying) {
                verify();
            }
            publish(AiConversationMediaUploadState.COMPLETED, totalBytes, 100, null);
        }

        private void verify() {
            verifying = true;
            transferredBytes = totalBytes;
            publish(AiConversationMediaUploadState.VERIFYING, totalBytes, 99, null);
        }

        private void failed() {
            publish(
                    AiConversationMediaUploadState.FAILED,
                    transferredBytes,
                    percentage(transferredBytes),
                    AiConversationAttachment.STORAGE_FAILURE_CODE);
        }

        private int percentage(long transferred) {
            if (totalBytes <= 0L) {
                return 0;
            }
            return Math.min(99, (int) ((transferred * 100L) / totalBytes));
        }

        private void publish(
                AiConversationMediaUploadState state,
                long transferred,
                Integer percent,
                String errorCode) {
            long candidateSequence = sequence.get() + 1L;
            AiConversationMediaUploadProgress progress = new AiConversationMediaUploadProgress(
                    AiConversationMediaType.IMAGE,
                    outputIndex,
                    attempt,
                    maxAttempts,
                    state,
                    Math.min(totalBytes, Math.max(0L, transferred)),
                    totalBytes,
                    percent,
                    candidateSequence,
                    errorCode);
            if (!throttle.shouldPublish(progress)) {
                return;
            }
            sequence.incrementAndGet();
            try {
                listener.onProgress(progress);
            } catch (RuntimeException ignored) {
                // 进度只影响展示；OSS 结果仍由当前上传事务决定。
            }
        }
    }
}
