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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 实现任意文件的会话附件生命周期，所有 Object Key 均由服务端重建且单个落盘操作执行有限重试。
 */
@Service
public final class AiConversationAttachmentServiceImpl
        implements AiConversationAttachmentService {

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

    public AiConversationAttachmentServiceImpl(
            AiConversationAttachmentObjectStorage storage,
            AiConversationAttachmentObjectKeyFactory keyFactory,
            AiConversationAttachmentProperties properties,
            Clock clock) {
        this.storage = Objects.requireNonNull(storage);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
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
        List<AiConversationAttachment> finalizedInput = new ArrayList<>();
        List<AiConversationAttachment> finalizedResponse = new ArrayList<>();
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
        long generatedTotal = 0L;
        for (int index = 0; index < safeGeneratedMedia.size(); index++) {
            AiConversationGeneratedMedia media = safeGeneratedMedia.get(index);
            String attachmentId = NanoId.randomNanoId(ATTACHMENT_ID_LENGTH);
            String fileName = keyFactory.sanitizeFileName(media.fileName());
            String contentType = normalizeContentType(media.contentType());
            byte[] bytes = media.bytes();
            boolean withinCount = index < properties.maxFilesPerMessage();
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
            try {
                if (!withinCount
                        || !withinTotal
                        || bytes.length == 0
                        || bytes.length > properties.maxFileBytes()) {
                    throw new IllegalArgumentException("Generated media size is invalid");
                }
                String publicUrl = retry(() -> storage.putPublic(
                        finalKey,
                        bytes,
                        contentType));
                createdKeys.add(finalKey);
                finalizedResponse.add(AiConversationAttachment.available(
                        attachmentId,
                        fileName,
                        contentType,
                        bytes.length,
                        category,
                        publicUrl));
            } catch (RuntimeException exception) {
                partial = true;
                finalizedResponse.add(AiConversationAttachment.storageFailed(
                        attachmentId,
                        fileName,
                        contentType,
                        bytes.length,
                        category));
                recordStorageFailure("generated");
            }
        }
        return new AiConversationAttachmentFinalization(
                finalizedInput,
                finalizedResponse,
                createdKeys,
                partial);
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

    @FunctionalInterface
    private interface StorageAction {
        String run();
    }
}
