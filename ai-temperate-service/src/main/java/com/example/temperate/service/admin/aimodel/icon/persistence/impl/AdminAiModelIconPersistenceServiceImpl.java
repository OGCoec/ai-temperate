package com.example.temperate.service.admin.aimodel.icon.persistence.impl;

import com.example.temperate.mapper.ai.AiModelIconMapper;
import com.example.temperate.model.ai.entity.AiModelIcon;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import com.example.temperate.service.admin.aimodel.icon.persistence.AdminAiModelIconPersistenceService;
import com.example.temperate.service.admin.aimodel.transaction.AiModelAfterCommitExecutor;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在 PostgreSQL 本地事务中写入模型图标，并在 URL 影响启用模型时登记提交后缓存刷新。
 *
 * <p>图标删除先锁定资源行再检查模型引用，模型写入则对同一资源取得共享锁；两条路径由
 * PostgreSQL 行锁串行化，以补偿没有物理外键的完整性风险。</p>
 */
@Service
public final class AdminAiModelIconPersistenceServiceImpl
        implements AdminAiModelIconPersistenceService {

    private final AiModelIconMapper iconMapper;
    private final AiModelCacheService cacheService;
    private final AiModelAfterCommitExecutor afterCommitExecutor;

    public AdminAiModelIconPersistenceServiceImpl(
            AiModelIconMapper iconMapper,
            AiModelCacheService cacheService,
            AiModelAfterCommitExecutor afterCommitExecutor) {
        this.iconMapper = iconMapper;
        this.cacheService = cacheService;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    @Override
    @Transactional
    public AiModelIcon create(AiModelIcon icon) {
        if (icon == null || icon.getId() == null || icon.getId() < 1) {
            throw new IllegalArgumentException(
                    "AI model icon requires a positive application ID.");
        }
        try {
            if (iconMapper.insert(icon) != 1) {
                throw new IllegalStateException(
                        "AI model icon insert affected an unexpected row count.");
            }
        } catch (DuplicateKeyException exception) {
            throw duplicate(exception);
        }
        return requireIcon(icon.getId());
    }

    @Override
    @Transactional
    public AiModelIcon update(
            long id,
            String iconName,
            String iconUrl,
            String objectKey,
            String description) {
        AiModelIcon current = iconMapper.findByIdForUpdate(id);
        if (current == null) {
            throw notFound();
        }
        AiModelIcon changed = new AiModelIcon();
        changed.setId(id);
        changed.setIconName(iconName);
        changed.setIconUrl(iconUrl);
        changed.setObjectKey(objectKey);
        changed.setDescription(description);
        try {
            if (iconMapper.update(changed) != 1) {
                throw new IllegalStateException(
                        "AI model icon update affected an unexpected row count.");
            }
        } catch (DuplicateKeyException exception) {
            throw duplicate(exception);
        }
        if (!Objects.equals(current.getIconUrl(), iconUrl)
                && iconMapper.existsEnabledReference(id)) {
            afterCommitExecutor.execute(cacheService::refreshEnabledSnapshot);
        }
        return requireIcon(id);
    }

    @Override
    @Transactional
    public AiModelIcon delete(long id) {
        AiModelIcon current = iconMapper.findByIdForUpdate(id);
        if (current == null) {
            throw notFound();
        }
        if (iconMapper.countModelReferences(id) > 0) {
            throw new AiModelIconException(
                    AiModelIconErrorCode.AI_MODEL_ICON_IN_USE,
                    "AI model icon is still referenced.");
        }
        if (iconMapper.deleteById(id) != 1) {
            throw new IllegalStateException(
                    "AI model icon delete affected an unexpected row count.");
        }
        return current;
    }

    private AiModelIcon requireIcon(long id) {
        AiModelIcon icon = iconMapper.findById(id);
        if (icon == null) {
            throw new IllegalStateException("Written AI model icon could not be reloaded.");
        }
        return icon;
    }

    private static AiModelIconException duplicate(DuplicateKeyException exception) {
        String message = String.valueOf(exception.getMostSpecificCause().getMessage());
        AiModelIconErrorCode code = message.contains("uk_ai_model_icon_object_key")
                ? AiModelIconErrorCode.AI_MODEL_ICON_OBJECT_CONFLICT
                : AiModelIconErrorCode.AI_MODEL_ICON_NAME_CONFLICT;
        return new AiModelIconException(code, "AI model icon uniqueness conflict.", exception);
    }

    private static AiModelIconException notFound() {
        return new AiModelIconException(
                AiModelIconErrorCode.AI_MODEL_ICON_NOT_FOUND,
                "AI model icon does not exist.");
    }
}
