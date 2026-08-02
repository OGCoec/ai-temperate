package com.example.temperate.service.admin.aimodel.availability.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.ai.AiModelMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 验证启用模型强确认使用单次批量数据库查询，空集合不会产生无意义 I/O。
 */
@ExtendWith(MockitoExtension.class)
final class AiModelAvailabilityServiceImplTest {

    @Mock
    private AiModelMapper modelMapper;

    @Test
    void confirmsEnabledIdsWithOneBatchQuery() {
        AiModelAvailabilityServiceImpl service =
                new AiModelAvailabilityServiceImpl(modelMapper);
        when(modelMapper.findEnabledIds(List.of(11L, 12L, 13L)))
                .thenReturn(List.of(11L, 13L));

        Set<Long> enabled = service.findEnabledIds(List.of(11L, 12L, 13L));

        assertThat(enabled).containsExactlyInAnyOrder(11L, 13L);
        verify(modelMapper).findEnabledIds(List.of(11L, 12L, 13L));
    }

    @Test
    void emptyCandidateListSkipsDatabase() {
        AiModelAvailabilityServiceImpl service =
                new AiModelAvailabilityServiceImpl(modelMapper);

        assertThat(service.findEnabledIds(List.of())).isEmpty();
        verifyNoInteractions(modelMapper);
    }
}
