package com.example.temperate.service.user.avatar.impl;

import cn.hutool.core.lang.id.NanoId;
import com.example.temperate.service.user.avatar.AvatarActivation;
import com.example.temperate.service.user.avatar.AvatarConfirmation;
import com.example.temperate.service.user.avatar.AvatarImageFormat;
import com.example.temperate.service.user.avatar.AvatarImageValidator;
import com.example.temperate.service.user.avatar.AvatarObjectKeyFactory;
import com.example.temperate.service.user.avatar.AvatarObjectMetadata;
import com.example.temperate.service.user.avatar.AvatarPreupload;
import com.example.temperate.service.user.avatar.UserAvatarErrorCode;
import com.example.temperate.service.user.avatar.UserAvatarException;
import com.example.temperate.service.user.avatar.UserAvatarObjectStorage;
import com.example.temperate.service.user.avatar.UserAvatarPersistenceService;
import com.example.temperate.service.user.avatar.UserAvatarService;
import io.micrometer.core.instrument.Metrics;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 编排普通用户头像的无状态预上传、精确取消和同步确认流程。
 *
 * <p>确认流程严格按 OSS 校验、服务端复制和数据库事务激活执行，不持久化对象清理任务，也不访问 Redis、Token 或 RabbitMQ。</p>
 */
@Service
public final class UserAvatarServiceImpl implements UserAvatarService {

    private static final Logger log = LoggerFactory.getLogger(UserAvatarServiceImpl.class);
    public static final long MAX_FILE_BYTES = 5L * 1024L * 1024L;
    private static final int NANO_ID_LENGTH = 24;

    private final UserAvatarObjectStorage storage;
    private final UserAvatarPersistenceService persistence;
    private final AvatarImageValidator imageValidator;
    private final AvatarObjectKeyFactory keyFactory;
    private final Clock clock;
    private final Supplier<String> imageIdSupplier;

    @Autowired
    public UserAvatarServiceImpl(
            UserAvatarObjectStorage storage,
            UserAvatarPersistenceService persistence,
            AvatarImageValidator imageValidator,
            AvatarObjectKeyFactory keyFactory,
            Clock clock) {
        this(
                storage,
                persistence,
                imageValidator,
                keyFactory,
                clock,
                () -> NanoId.randomNanoId(NANO_ID_LENGTH));
    }

    UserAvatarServiceImpl(
            UserAvatarObjectStorage storage,
            UserAvatarPersistenceService persistence,
            AvatarImageValidator imageValidator,
            AvatarObjectKeyFactory keyFactory,
            Clock clock,
            Supplier<String> imageIdSupplier) {
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.persistence = Objects.requireNonNull(persistence, "persistence must not be null");
        this.imageValidator = Objects.requireNonNull(imageValidator, "imageValidator must not be null");
        this.keyFactory = Objects.requireNonNull(keyFactory, "keyFactory must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.imageIdSupplier = Objects.requireNonNull(imageIdSupplier, "imageIdSupplier must not be null");
    }

    @Override
    public AvatarPreupload createPreupload(
            long userId,
            String publicUserId,
            AvatarImageFormat format,
            long sizeBytes) {
        requireUserId(userId);
        requireSize(sizeBytes);
        String imageId = keyFactory.requireImageId(imageIdSupplier.get());
        String temporaryKey = keyFactory.temporaryKey(publicUserId, imageId, format);
        try {
            UserAvatarObjectStorage.PresignedPut signed =
                    storage.generatePresignedPutUrl(temporaryKey, format.contentType());
            if (signed.expiresAt() == null || !signed.expiresAt().isAfter(clock.instant())) {
                throw new UserAvatarObjectStorage.StorageException(
                        "OSS returned an expired pre-signed URL",
                        null);
            }
            Map<String, String> uploadHeaders = Map.of(
                    "Content-Type", format.contentType(),
                    "x-oss-object-acl", "private",
                    "x-oss-forbid-overwrite", "true");
            return new AvatarPreupload(
                    imageId,
                    signed.uploadUrl(),
                    "PUT",
                    uploadHeaders,
                    signed.expiresAt());
        } catch (RuntimeException exception) {
            throw storageUnavailable("暂时无法创建头像上传地址。", exception);
        }
    }

    @Override
    public void cancel(
            long userId,
            String publicUserId,
            String preuploadId,
            AvatarImageFormat format) {
        requireUserId(userId);
        String temporaryKey = keyFactory.temporaryKey(publicUserId, preuploadId, format);
        try {
            storage.deleteObject(temporaryKey);
        } catch (UserAvatarObjectStorage.ObjectNotFoundException ignored) {
            // 取消接口必须幂等，对象已不存在和删除成功都返回 204。
        } catch (RuntimeException exception) {
            throw storageUnavailable("暂时无法取消头像预上传。", exception);
        }
    }

    @Override
    public AvatarConfirmation confirm(
            long userId,
            String publicUserId,
            String preuploadId,
            AvatarImageFormat format) {
        requireUserId(userId);
        String temporaryKey = keyFactory.temporaryKey(publicUserId, preuploadId, format);
        String finalKey = keyFactory.finalKey(publicUserId, preuploadId, format);

        AvatarActivation current;
        try {
            current = persistence.findCurrent(userId);
        } catch (RuntimeException exception) {
            throw new UserAvatarException(
                    UserAvatarErrorCode.PERSISTENCE_FAILED,
                    "暂时无法读取当前头像状态。",
                    exception);
        }
        if (current == null) {
            throw new UserAvatarException(
                    UserAvatarErrorCode.PROFILE_UNAVAILABLE,
                    "当前用户资料不存在或不可用。");
        }

        AvatarObjectMetadata objectMetadata = requireTemporaryObject(temporaryKey);
        validateHeadMetadata(objectMetadata, format, temporaryKey);
        byte[] imageBytes = downloadTemporaryObject(temporaryKey);
        try {
            imageValidator.validate(imageBytes, format);
        } catch (UserAvatarException exception) {
            discardInvalidTemporaryObject(temporaryKey);
            throw exception;
        }

        String avatarUrl;
        try {
            avatarUrl = storage.copyObjectToPublic(
                    temporaryKey,
                    finalKey,
                    format.contentType());
        } catch (RuntimeException exception) {
            throw storageUnavailable("暂时无法激活头像。", exception);
        }

        AvatarActivation activation;
        try {
            // 数据库事务提交成功后方法才返回，接口不会在头像尚未真正激活时报告成功。
            activation = persistence.activate(
                    userId,
                    avatarUrl);
        } catch (RuntimeException exception) {
            if (!isAlreadyActivatedByConcurrentRequest(userId, avatarUrl)) {
                compensateCopiedObject(finalKey);
            }
            if (exception instanceof UserAvatarException userAvatarException) {
                throw userAvatarException;
            }
            throw new UserAvatarException(
                    UserAvatarErrorCode.PERSISTENCE_FAILED,
                    "头像已复制但资料更新失败，原头像保持不变。",
                    exception);
        }

        return new AvatarConfirmation(activation.avatarUrl());
    }

    private AvatarObjectMetadata requireTemporaryObject(String temporaryKey) {
        try {
            return storage.headObject(temporaryKey);
        } catch (UserAvatarObjectStorage.ObjectNotFoundException exception) {
            throw new UserAvatarException(
                    UserAvatarErrorCode.TEMP_OBJECT_NOT_FOUND,
                    "头像预上传已不存在或已过期。",
                    exception);
        } catch (RuntimeException exception) {
            throw storageUnavailable("暂时无法检查头像预上传。", exception);
        }
    }

    private void validateHeadMetadata(
            AvatarObjectMetadata metadata,
            AvatarImageFormat format,
            String temporaryKey) {
        if (metadata == null
                || metadata.sizeBytes() <= 0L
                || metadata.sizeBytes() > MAX_FILE_BYTES
                || !format.matchesContentType(metadata.contentType())) {
            discardInvalidTemporaryObject(temporaryKey);
            throw new UserAvatarException(
                    UserAvatarErrorCode.INVALID_IMAGE,
                    "头像大小或 Content-Type 不符合要求。");
        }
    }

    private byte[] downloadTemporaryObject(String temporaryKey) {
        try {
            return storage.downloadObjectBytesBounded(temporaryKey, MAX_FILE_BYTES);
        } catch (UserAvatarObjectStorage.ObjectTooLargeException exception) {
            discardInvalidTemporaryObject(temporaryKey);
            throw new UserAvatarException(
                    UserAvatarErrorCode.INVALID_IMAGE,
                    "头像大小超过 5 MB。",
                    exception);
        } catch (RuntimeException exception) {
            throw storageUnavailable("暂时无法读取头像内容。", exception);
        }
    }

    private boolean isAlreadyActivatedByConcurrentRequest(long userId, String avatarUrl) {
        try {
            AvatarActivation committed = persistence.findCurrent(userId);
            return committed != null && avatarUrl.equals(committed.avatarUrl());
        } catch (RuntimeException ignored) {
            // 数据库仍不可用时按复制补偿原则删除本次目标，避免确认失败持续产生孤儿正式对象。
            return false;
        }
    }

    private void discardInvalidTemporaryObject(String temporaryKey) {
        try {
            storage.deleteObject(temporaryKey);
        } catch (RuntimeException exception) {
            Metrics.counter("user.avatar.temporary.cleanup.failures").increment();
            log.warn(
                    "Invalid avatar temporary object cleanup failed, errorType={}",
                    exception.getClass().getName());
        }
    }

    private void compensateCopiedObject(String finalKey) {
        try {
            storage.deleteObject(finalKey);
        } catch (RuntimeException exception) {
            Metrics.counter("user.avatar.copy.compensation.failures").increment();
            log.error(
                    "Avatar copy compensation failed, errorType={}",
                    exception.getClass().getName());
        }
    }

    private static void requireUserId(long userId) {
        if (userId <= 0L) {
            throw new UserAvatarException(
                    UserAvatarErrorCode.INVALID_INPUT,
                    "当前用户身份无效。");
        }
    }

    private static void requireSize(long sizeBytes) {
        if (sizeBytes <= 0L || sizeBytes > MAX_FILE_BYTES) {
            throw new UserAvatarException(
                    UserAvatarErrorCode.INVALID_INPUT,
                    "头像大小必须大于 0 且不超过 5 MB。");
        }
    }

    private static UserAvatarException storageUnavailable(
            String message,
            RuntimeException exception) {
        if (exception instanceof UserAvatarException userAvatarException) {
            return userAvatarException;
        }
        return new UserAvatarException(
                UserAvatarErrorCode.STORAGE_UNAVAILABLE,
                message,
                exception);
    }
}
