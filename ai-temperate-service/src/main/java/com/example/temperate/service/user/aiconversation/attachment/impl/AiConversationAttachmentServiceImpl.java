package com.example.temperate.service.user.aiconversation.attachment.impl;

import cn.hutool.core.lang.id.NanoId;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentCategory;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentFinalization;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentObjectKeyFactory;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentObjectStorage;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentService;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentUploadReference;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationGeneratedMedia;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationPreupload;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationPreuploadBatch;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationPreuploadFile;
import com.example.temperate.service.user.aiconversation.attachment.config.AiConversationAttachmentProperties;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import io.micrometer.core.instrument.Metrics;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
        List<AiConversationAttachment> finalizedInput = new ArrayList<>();
        List<String> createdKeys = new ArrayList<>();
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
                String publicUrl = retry(() -> storage.copyToPublic(
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
        List<GeneratedUploadPlan> uploadPlans = new ArrayList<>(safeGeneratedMedia.size());
        long generatedTotal = 0L;
        for (int index = 0; index < safeGeneratedMedia.size(); index++) {
            AiConversationGeneratedMedia media = safeGeneratedMedia.get(index);
            String attachmentId = NanoId.randomNanoId(ATTACHMENT_ID_LENGTH);
            String fileName = keyFactory.sanitizeFileName(media.fileName());
            String contentType = normalizeContentType(media.contentType());
            byte[] bytes = media.bytes();
            // 用户输入仍受八个附件限制；模型生成输出使用独立的十张上限，二者不得混用。
            boolean withinCount = index < MAXIMUM_GENERATED_IMAGE_OUTPUTS;
            boolean withinTotal = bytes.length <= properties.maxTotalBytesPerMessage()
                    - generatedTotal;
            if (withinCount && withinTotal) {
                generatedTotal += bytes.length;
            }
            String finalKey = keyFactory.finalKey(
                    userPublicId,
                    conversationPublicId,
                    messagePublicId,
                    attachmentId,
                    fileName);
            AiConversationAttachmentCategory category = classify(fileName, contentType);
            uploadPlans.add(new GeneratedUploadPlan(
                    attachmentId,
                    fileName,
                    contentType,
                    bytes,
                    category,
                    finalKey,
                    withinCount
                            && withinTotal
                            && bytes.length > 0
                            && bytes.length <= properties.maxFileBytes()));
        }
        GeneratedUploadGuard uploadGuard = new GeneratedUploadGuard();
        List<CompletableFuture<GeneratedUploadResult>> futures =
                new ArrayList<>(uploadPlans.size());
        try {
            for (GeneratedUploadPlan plan : uploadPlans) {
                // 这里只提交内存任务，不在循环中执行 OSS I/O；结果仍按计划顺序保存。
                futures.add(CompletableFuture.supplyAsync(
                        () -> uploadGenerated(plan, uploadGuard),
                        finalizationExecutor));
            }
        } catch (RuntimeException rejected) {
            List<String> generatedKeys = uploadGuard.closeAndSnapshot();
            futures.forEach(future -> future.cancel(true));
            List<String> cleanup = new ArrayList<>(createdKeys.size() + generatedKeys.size());
            cleanup.addAll(createdKeys);
            cleanup.addAll(generatedKeys);
            compensateCreatedObjects(cleanup);
            throw storageUnavailable(
                    "附件最终化队列繁忙。",
                    new IllegalStateException("Attachment finalization executor rejected task", rejected));
        }
        List<GeneratedUploadResult> generatedResults;
        try {
            generatedResults = awaitGeneratedUploads(futures, timeout);
        } catch (RuntimeException failure) {
            List<String> generatedKeys = uploadGuard.closeAndSnapshot();
            futures.forEach(future -> future.cancel(true));
            List<String> cleanup = new ArrayList<>(createdKeys.size() + generatedKeys.size());
            cleanup.addAll(createdKeys);
            cleanup.addAll(generatedKeys);
            compensateCreatedObjects(cleanup);
            throw failure;
        }
        List<AiConversationAttachment> finalizedResponse = new ArrayList<>(generatedResults.size());
        for (GeneratedUploadResult result : generatedResults) {
            finalizedResponse.add(result.attachment());
            if (result.createdObjectKey() != null) {
                createdKeys.add(result.createdObjectKey());
            }
            partial |= result.failed();
        }
        return new AiConversationAttachmentFinalization(
                finalizedInput,
                finalizedResponse,
                createdKeys,
                partial);
    }

    private GeneratedUploadResult uploadGenerated(
            GeneratedUploadPlan plan,
            GeneratedUploadGuard uploadGuard) {
        if (!plan.valid()) {
            recordStorageFailure("generated");
            return GeneratedUploadResult.failed(plan);
        }
        try {
            String publicUrl = retry(() -> storage.putPublic(
                    plan.finalKey(),
                    plan.bytes(),
                    plan.contentType()));
            // 超时线程先关闭 Guard；晚到的 OSS 成功不得逃逸为无人引用对象。
            if (!uploadGuard.accept(plan.finalKey())) {
                compensateCreatedObjects(List.of(plan.finalKey()));
                return GeneratedUploadResult.failed(plan);
            }
            return new GeneratedUploadResult(
                    AiConversationAttachment.available(
                            plan.attachmentId(),
                            plan.fileName(),
                            plan.contentType(),
                            plan.bytes().length,
                            plan.category(),
                            publicUrl),
                    plan.finalKey(),
                    false);
        } catch (RuntimeException exception) {
            recordStorageFailure("generated");
            return GeneratedUploadResult.failed(plan);
        }
    }

    private List<GeneratedUploadResult> awaitGeneratedUploads(
            List<CompletableFuture<GeneratedUploadResult>> futures,
            Duration timeout) {
        long deadline = deadlineAfter(timeout);
        List<GeneratedUploadResult> results = new ArrayList<>(futures.size());
        try {
            for (CompletableFuture<GeneratedUploadResult> future : futures) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    throw new TimeoutException("Attachment finalization deadline expired");
                }
                results.add(future.get(remaining, TimeUnit.NANOSECONDS));
            }
            return List.copyOf(results);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw storageUnavailable(
                    "附件最终化被中断。",
                    new IllegalStateException("Attachment finalization interrupted", exception));
        } catch (TimeoutException exception) {
            throw storageUnavailable(
                    "附件最终化超时。",
                    new IllegalStateException("Attachment finalization timed out", exception));
        } catch (ExecutionException exception) {
            throw storageUnavailable(
                    "附件最终化执行失败。",
                    new IllegalStateException("Attachment finalization task failed", exception));
        }
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
                return action.run();
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
            String attachmentId,
            String fileName,
            String contentType,
            byte[] bytes,
            AiConversationAttachmentCategory category,
            String finalKey,
            boolean valid) {
    }

    /**
     * 把并发完成结果恢复成请求顺序，同时显式标记该槽位是否需要进入部分失败统计。
     */
    private record GeneratedUploadResult(
            AiConversationAttachment attachment,
            String createdObjectKey,
            boolean failed) {

        private static GeneratedUploadResult failed(GeneratedUploadPlan plan) {
            return new GeneratedUploadResult(
                    AiConversationAttachment.storageFailed(
                            plan.attachmentId(),
                            plan.fileName(),
                            plan.contentType(),
                            plan.bytes().length,
                            plan.category()),
                    null,
                    true);
        }
    }

    /**
     * 在最终化超时与晚到 OSS 成功之间建立互斥边界，保证每个已创建对象要么被返回，要么被补偿。
     */
    private static final class GeneratedUploadGuard {

        private final List<String> acceptedKeys = new ArrayList<>();
        private boolean closed;

        private synchronized boolean accept(String objectKey) {
            if (closed) {
                return false;
            }
            acceptedKeys.add(objectKey);
            return true;
        }

        private synchronized List<String> closeAndSnapshot() {
            closed = true;
            return List.copyOf(acceptedKeys);
        }
    }

    @FunctionalInterface
    private interface StorageAction {
        String run();
    }
}
