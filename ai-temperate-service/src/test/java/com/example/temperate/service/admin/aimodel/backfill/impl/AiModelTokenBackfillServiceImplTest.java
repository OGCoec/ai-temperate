package com.example.temperate.service.admin.aimodel.backfill.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.ai.AiModelMapper;
import com.example.temperate.model.ai.entity.AiModel;
import com.example.temperate.model.ai.entity.AiModelSearchTokenUpdate;
import com.example.temperate.service.admin.aimodel.backfill.AiModelTokenBackfillBatchResult;
import com.example.temperate.service.admin.aimodel.text.AiModelTextTokenizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 验证 AI 模型词元回填每批只执行一次读取和一次批量更新，并严格限制批量边界。
 */
@ExtendWith(MockitoExtension.class)
final class AiModelTokenBackfillServiceImplTest {

    @Mock
    private AiModelMapper modelMapper;
    @Mock
    private AiModelTextTokenizer tokenizer;

    private AiModelTokenBackfillServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiModelTokenBackfillServiceImpl(
                modelMapper,
                tokenizer,
                new ObjectMapper());
    }

    @Test
    void backfillsOnePageWithOneBatchUpdate() {
        AiModel first = model(11L, "模型甲", "描述甲");
        AiModel second = model(19L, "模型乙", null);
        when(modelMapper.findTokenBackfillPage(0L, 500))
                .thenReturn(List.of(first, second));
        when(tokenizer.tokenize("模型甲")).thenReturn(List.of("模型", "甲"));
        when(tokenizer.tokenize("描述甲")).thenReturn(List.of("描述", "甲"));
        when(tokenizer.tokenize("模型乙")).thenReturn(List.of("模型", "乙"));
        when(tokenizer.tokenize(null)).thenReturn(List.of());
        when(modelMapper.updateSearchTokensBatch(org.mockito.ArgumentMatchers.any()))
                .thenReturn(2);

        AiModelTokenBackfillBatchResult result = service.backfillAfter(0L, 500);

        assertThat(result.lastId()).isEqualTo(19L);
        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.updated()).isEqualTo(2);
        assertThat(result.hasMore()).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiModelSearchTokenUpdate>> updates =
                ArgumentCaptor.forClass(List.class);
        verify(modelMapper).findTokenBackfillPage(0L, 500);
        verify(modelMapper).updateSearchTokensBatch(updates.capture());
        assertThat(updates.getValue()).hasSize(2);
        assertThat(updates.getValue().get(0).modelNameTokensJson())
                .isEqualTo("[\"模型\",\"甲\"]");
        verifyNoMoreInteractions(modelMapper);
    }

    @Test
    void reportsMoreWhenBatchIsFullAndDoesNotWriteEmptyPage() {
        List<AiModel> fullPage = java.util.stream.LongStream.rangeClosed(1, 500)
                .mapToObj(id -> model(id, "m" + id, null))
                .toList();
        when(modelMapper.findTokenBackfillPage(0L, 500)).thenReturn(fullPage);
        when(tokenizer.tokenize(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("token"));
        when(tokenizer.tokenize(null)).thenReturn(List.of());
        when(modelMapper.updateSearchTokensBatch(org.mockito.ArgumentMatchers.any()))
                .thenReturn(500);

        assertThat(service.backfillAfter(0L, 500).hasMore()).isTrue();

        when(modelMapper.findTokenBackfillPage(500L, 500)).thenReturn(List.of());
        AiModelTokenBackfillBatchResult empty = service.backfillAfter(500L, 500);
        assertThat(empty.scanned()).isZero();
        assertThat(empty.updated()).isZero();
        assertThat(empty.hasMore()).isFalse();
    }

    @Test
    void rejectsOutOfRangeBatchSize() {
        assertThatThrownBy(() -> service.backfillAfter(0L, 501))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AiModel model(long id, String name, String description) {
        AiModel model = new AiModel();
        model.setId(id);
        model.setModelName(name);
        model.setDescription(description);
        return model;
    }
}
