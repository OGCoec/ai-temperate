package com.example.temperate.service.admin.aimodel.icon.persistence.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.ai.AiModelIconMapper;
import com.example.temperate.model.ai.entity.AiModelIcon;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import com.example.temperate.service.admin.aimodel.transaction.AiModelAfterCommitExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 验证模型图标短事务在 URL 变化、启用模型引用和删除引用之间保持正确的缓存与锁定语义。
 */
@ExtendWith(MockitoExtension.class)
final class AdminAiModelIconPersistenceServiceImplTest {

    private static final long ICON_ID = 17L;

    @Mock
    private AiModelIconMapper iconMapper;
    @Mock
    private AiModelCacheService cacheService;
    @Mock
    private AiModelAfterCommitExecutor afterCommitExecutor;

    private AdminAiModelIconPersistenceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminAiModelIconPersistenceServiceImpl(
                iconMapper,
                cacheService,
                afterCommitExecutor);
    }

    @Test
    void urlChangeReferencedByEnabledModelRegistersOneAfterCommitRefresh() {
        AiModelIcon current = icon("OpenAI", "https://old.example.test/icon.png", null);
        AiModelIcon updated = icon("OpenAI", "https://new.example.test/icon.png", null);
        when(iconMapper.findByIdForUpdate(ICON_ID)).thenReturn(current);
        when(iconMapper.update(any(AiModelIcon.class))).thenReturn(1);
        when(iconMapper.existsEnabledReference(ICON_ID)).thenReturn(true);
        when(iconMapper.findById(ICON_ID)).thenReturn(updated);

        service.update(
                ICON_ID,
                updated.getIconName(),
                updated.getIconUrl(),
                null,
                updated.getDescription());

        ArgumentCaptor<Runnable> refresh = ArgumentCaptor.forClass(Runnable.class);
        verify(afterCommitExecutor).execute(refresh.capture());
        verifyNoInteractions(cacheService);
        refresh.getValue().run();
        verify(cacheService).refreshEnabledSnapshot();
    }

    @Test
    void nameOnlyChangeDoesNotRefreshEnabledModelCache() {
        AiModelIcon current = icon("OpenAI", "https://example.test/icon.png", null);
        AiModelIcon updated = icon("OpenAI GPT", "https://example.test/icon.png", null);
        when(iconMapper.findByIdForUpdate(ICON_ID)).thenReturn(current);
        when(iconMapper.update(any(AiModelIcon.class))).thenReturn(1);
        when(iconMapper.findById(ICON_ID)).thenReturn(updated);

        service.update(
                ICON_ID,
                updated.getIconName(),
                updated.getIconUrl(),
                null,
                updated.getDescription());

        verify(iconMapper, never()).existsEnabledReference(ICON_ID);
        verifyNoInteractions(afterCommitExecutor, cacheService);
    }

    @Test
    void createRejectsAnIconWithoutAPositiveApplicationId() {
        AiModelIcon icon = icon("OpenAI", "https://example.test/icon.png", null);
        icon.setId(null);

        assertThatThrownBy(() -> service.create(icon))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive application ID");

        verifyNoInteractions(iconMapper, afterCommitExecutor, cacheService);
    }

    @Test
    void referencedIconCannotBeDeleted() {
        when(iconMapper.findByIdForUpdate(ICON_ID))
                .thenReturn(icon("OpenAI", "https://example.test/icon.png", null));
        when(iconMapper.countModelReferences(ICON_ID)).thenReturn(2);

        assertThatThrownBy(() -> service.delete(ICON_ID))
                .isInstanceOfSatisfying(AiModelIconException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                AiModelIconErrorCode.AI_MODEL_ICON_IN_USE));

        verify(iconMapper, never()).deleteById(ICON_ID);
    }

    private static AiModelIcon icon(String name, String url, String objectKey) {
        AiModelIcon icon = new AiModelIcon();
        icon.setId(ICON_ID);
        icon.setIconName(name);
        icon.setIconUrl(url);
        icon.setObjectKey(objectKey);
        icon.setDescription("模型厂商图标");
        return icon;
    }
}
