package com.example.temperate.service.admin.aimodel.icon.service.impl;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.id.snowflake.component.SnowflakeIdWorker;
import com.example.temperate.mapper.ai.AiModelIconMapper;
import com.example.temperate.model.ai.entity.AiModelIcon;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import com.example.temperate.service.admin.aimodel.icon.dto.AdminAiModelIconPatchCommand;
import com.example.temperate.service.admin.aimodel.icon.dto.AdminAiModelIconRemoteCreateCommand;
import com.example.temperate.service.admin.aimodel.icon.dto.AdminAiModelIconUploadCommand;
import com.example.temperate.service.admin.aimodel.icon.dto.AiModelIconPageResult;
import com.example.temperate.service.admin.aimodel.icon.dto.AiModelIconResult;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageMetadata;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageValidator;
import com.example.temperate.service.admin.aimodel.icon.persistence.AdminAiModelIconPersistenceService;
import com.example.temperate.service.admin.aimodel.icon.remote.AiModelIconRemoteImageValidator;
import com.example.temperate.service.admin.aimodel.icon.service.AdminAiModelIconService;
import com.example.temperate.service.admin.aimodel.icon.storage.AiModelIconObjectKeyFactory;
import com.example.temperate.service.admin.aimodel.icon.storage.AiModelIconObjectStorage;
import com.example.temperate.service.admin.aimodel.icon.storage.AiModelIconStorageException;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 编排模型图标分页查询、外部 URL 验证、本地图片上传和数据库短事务写入。
 *
 * <p>OSS 与 HTTP I/O 均在数据库事务之外完成；跨系统失败只能执行尽力补偿，项目明确接受
 * 极少量 OSS 残留，不创建清理任务表。</p>
 */
@Service
public final class AdminAiModelIconServiceImpl implements AdminAiModelIconService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AdminAiModelIconServiceImpl.class);

    private final AiModelIconMapper iconMapper;
    private final AdminAiModelIconPersistenceService persistenceService;
    private final AiModelIconRemoteImageValidator remoteImageValidator;
    private final AiModelIconImageValidator imageValidator;
    private final AiModelIconObjectKeyFactory objectKeyFactory;
    private final AiModelIconObjectStorage objectStorage;
    private final SnowflakeIdWorker snowflakeIdWorker;
    private final PublicIdCodec publicIdCodec;
    private final Counter cleanupFailureCounter;

    public AdminAiModelIconServiceImpl(
            AiModelIconMapper iconMapper,
            AdminAiModelIconPersistenceService persistenceService,
            AiModelIconRemoteImageValidator remoteImageValidator,
            AiModelIconImageValidator imageValidator,
            AiModelIconObjectKeyFactory objectKeyFactory,
            AiModelIconObjectStorage objectStorage,
            SnowflakeIdWorker snowflakeIdWorker,
            PublicIdCodec publicIdCodec,
            MeterRegistry meterRegistry) {
        this.iconMapper = iconMapper;
        this.persistenceService = persistenceService;
        this.remoteImageValidator = remoteImageValidator;
        this.imageValidator = imageValidator;
        this.objectKeyFactory = objectKeyFactory;
        this.objectStorage = objectStorage;
        this.snowflakeIdWorker = snowflakeIdWorker;
        this.publicIdCodec = publicIdCodec;
        this.cleanupFailureCounter = meterRegistry.counter(
                "ai.model.icon.oss.cleanup.failed");
    }

    @Override
    @Transactional(readOnly = true)
    public AiModelIconPageResult list(int pageNum, int pageSize) {
        if (pageNum < 1 || pageSize < 1 || pageSize > 100) {
            throw invalidInput();
        }
        PageInfo<AiModelIcon> page;
        try {
            page = PageHelper.startPage(pageNum, pageSize, true)
                    .doSelectPageInfo(iconMapper::findPage);
        } finally {
            // PageHelper 使用线程本地状态，必须在能力批量查询或后续请求前清理。
            PageHelper.clearPage();
        }
        List<AiModelIconResult> results = page.getList().stream()
                .map(this::toResult)
                .toList();
        return new AiModelIconPageResult(
                results,
                page.getPageNum(),
                page.getPageSize(),
                page.getTotal(),
                page.getPages(),
                page.isHasPreviousPage(),
                page.isHasNextPage());
    }

    @Override
    @Transactional(readOnly = true)
    public AiModelIconResult detail(String publicId) {
        return toResult(requireIcon(decode(publicId)));
    }

    @Override
    public AiModelIconResult createRemote(AdminAiModelIconRemoteCreateCommand command) {
        if (command == null) {
            throw invalidInput();
        }
        String iconName = requiredName(command.iconName());
        String description = optionalDescription(command.description());
        String finalUrl = remoteImageValidator.validate(command.iconUrl()).finalUrl();
        AiModelIcon draft = icon(iconName, finalUrl, null, description);
        return toResult(persistenceService.create(draft));
    }

    @Override
    public AiModelIconResult createUpload(AdminAiModelIconUploadCommand command) {
        if (command == null) {
            throw invalidInput();
        }
        String iconName = requiredName(command.iconName());
        String description = optionalDescription(command.description());
        byte[] bytes = requireFile(command.bytes());
        AiModelIconImageMetadata metadata =
                imageValidator.validate(bytes, command.contentType());
        String objectKey = createObjectKey(iconName, metadata);
        String iconUrl = putObject(
                objectKey,
                metadata.storageBytes(),
                metadata.format().contentType(),
                true);
        try {
            return toResult(persistenceService.create(
                    icon(iconName, iconUrl, objectKey, description)));
        } catch (RuntimeException exception) {
            bestEffortDelete(objectKey);
            throw exception;
        }
    }

    @Override
    public AiModelIconResult patch(
            String publicId,
            AdminAiModelIconPatchCommand command) {
        if (command == null || !command.hasChanges()) {
            throw invalidInput();
        }
        long id = decode(publicId);
        AiModelIcon current = requireIcon(id);
        String iconName = command.iconName().present()
                ? requiredName(command.iconName().value())
                : current.getIconName();
        String description = command.description().present()
                ? optionalDescription(command.description().value())
                : current.getDescription();
        String iconUrl = current.getIconUrl();
        String objectKey = current.getObjectKey();
        if (command.iconUrl().present()) {
            iconUrl = remoteImageValidator.validate(command.iconUrl().value()).finalUrl();
            objectKey = null;
        }
        AiModelIcon updated = persistenceService.update(
                id,
                iconName,
                iconUrl,
                objectKey,
                description);
        if (!Objects.equals(current.getObjectKey(), updated.getObjectKey())
                && current.getObjectKey() != null) {
            bestEffortDelete(current.getObjectKey());
        }
        return toResult(updated);
    }

    @Override
    public AiModelIconResult replaceFile(
            String publicId,
            byte[] sourceBytes,
            String contentType) {
        long id = decode(publicId);
        AiModelIcon current = requireIcon(id);
        byte[] bytes = requireFile(sourceBytes);
        AiModelIconImageMetadata metadata = imageValidator.validate(bytes, contentType);
        String objectKey = createObjectKey(current.getIconName(), metadata);
        boolean sameObject = Objects.equals(objectKey, current.getObjectKey());
        String iconUrl = putObject(
                objectKey,
                metadata.storageBytes(),
                metadata.format().contentType(),
                !sameObject);
        AiModelIcon updated;
        try {
            updated = persistenceService.update(
                    id,
                    current.getIconName(),
                    iconUrl,
                    objectKey,
                    current.getDescription());
        } catch (RuntimeException exception) {
            // 同路径覆盖失败时不能删除当前数据库仍引用的对象；不同路径的新对象可以安全补偿。
            if (!sameObject) {
                bestEffortDelete(objectKey);
            }
            throw exception;
        }
        if (!sameObject && current.getObjectKey() != null) {
            bestEffortDelete(current.getObjectKey());
        }
        return toResult(updated);
    }

    @Override
    public void delete(String publicId) {
        AiModelIcon deleted = persistenceService.delete(decode(publicId));
        if (deleted.getObjectKey() != null) {
            bestEffortDelete(deleted.getObjectKey());
        }
    }

    private AiModelIcon requireIcon(long id) {
        AiModelIcon icon = iconMapper.findById(id);
        if (icon == null) {
            throw new AiModelIconException(
                    AiModelIconErrorCode.AI_MODEL_ICON_NOT_FOUND,
                    "AI model icon does not exist.");
        }
        return icon;
    }

    private long decode(String publicId) {
        try {
            return publicIdCodec.decode(publicId);
        } catch (IllegalArgumentException exception) {
            throw new AiModelIconException(
                    AiModelIconErrorCode.AI_MODEL_ICON_PUBLIC_ID_INVALID,
                    "AI model icon public ID is invalid.",
                    exception);
        }
    }

    private AiModelIconResult toResult(AiModelIcon icon) {
        return new AiModelIconResult(
                publicIdCodec.encode(icon.getId()),
                icon.getIconName(),
                icon.getIconUrl(),
                icon.getDescription(),
                icon.getCreatedAt(),
                icon.getUpdatedAt());
    }

    private String putObject(
            String objectKey,
            byte[] bytes,
            String contentType,
            boolean forbidOverwrite) {
        try {
            return objectStorage.putObject(
                    objectKey,
                    bytes,
                    contentType,
                    forbidOverwrite);
        } catch (AiModelIconStorageException exception) {
            AiModelIconErrorCode code = exception.objectConflict()
                    ? AiModelIconErrorCode.AI_MODEL_ICON_OBJECT_CONFLICT
                    : AiModelIconErrorCode.AI_MODEL_ICON_STORAGE_UNAVAILABLE;
            throw new AiModelIconException(code, "AI model icon OSS operation failed.", exception);
        }
    }

    private void bestEffortDelete(String objectKey) {
        try {
            objectStorage.deleteObject(objectKey);
        } catch (RuntimeException exception) {
            cleanupFailureCounter.increment();
            // Object Key 可能包含业务名称，日志只保留异常类型和指标，避免输出完整路径或 SDK 响应。
            LOGGER.warn(
                    "event=ai_model_icon_oss_cleanup_failed exception_type={}",
                    exception.getClass().getSimpleName());
        }
    }

    private AiModelIcon icon(
            String iconName,
            String iconUrl,
            String objectKey,
            String description) {
        AiModelIcon icon = new AiModelIcon();
        icon.setId(nextIconId());
        icon.setIconName(iconName);
        icon.setIconUrl(iconUrl);
        icon.setObjectKey(objectKey);
        icon.setDescription(description);
        return icon;
    }

    private long nextIconId() {
        // 图标主键在进入数据库前生成，避免重新依赖 PostgreSQL Identity 的回填行为。
        long id = snowflakeIdWorker.nextId();
        if (id <= 0) {
            throw new IllegalStateException(
                    "AI model icon ID generator returned a non-positive ID.");
        }
        return id;
    }

    private static String requiredName(String value) {
        if (value == null || value.isBlank()) {
            throw invalidInput();
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            throw invalidInput();
        }
        return normalized;
    }

    private static String optionalDescription(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 512) {
            throw invalidInput();
        }
        return normalized;
    }

    private static byte[] requireFile(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw invalidInput();
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new AiModelIconException(
                    AiModelIconErrorCode.AI_MODEL_ICON_FILE_TOO_LARGE,
                    "AI model icon file exceeds two MiB.");
        }
        return bytes.clone();
    }

    private String createObjectKey(
            String iconName,
            AiModelIconImageMetadata metadata) {
        try {
            return objectKeyFactory.create(iconName, metadata.format());
        } catch (IllegalArgumentException exception) {
            throw new AiModelIconException(
                    AiModelIconErrorCode.AI_MODEL_ICON_INPUT_INVALID,
                    "AI model icon name cannot produce an object key.",
                    exception);
        }
    }

    private static AiModelIconException invalidInput() {
        return new AiModelIconException(
                AiModelIconErrorCode.AI_MODEL_ICON_INPUT_INVALID,
                "AI model icon input is invalid.");
    }
}
