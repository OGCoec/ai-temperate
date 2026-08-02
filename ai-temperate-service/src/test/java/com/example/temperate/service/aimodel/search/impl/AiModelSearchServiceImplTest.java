package com.example.temperate.service.aimodel.search.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.service.admin.aimodel.text.AiModelTextTokenizer;
import com.example.temperate.service.aimodel.search.AiModelSearchCriteria;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证模型目录搜索统一使用横杠名称词元、IK 描述词元和完整词元包含语义。
 */
final class AiModelSearchServiceImplTest {

    private final AiModelTextTokenizer descriptionTokenizer =
            mock(AiModelTextTokenizer.class);
    private final AiModelSearchServiceImpl service =
            new AiModelSearchServiceImpl(new ObjectMapper(), descriptionTokenizer);

    @Test
    void preparesDeterministicNameAndDescriptionCriteria() {
        when(descriptionTokenizer.tokenize("gpt-5.4-mini"))
                .thenReturn(List.of("5.4", "gpt", "mini"));

        AiModelSearchCriteria criteria = service.prepare("  GPT-5.4-Mini  ");

        assertThat(criteria.vendorExact()).isEqualTo("gpt-5.4-mini");
        assertThat(criteria.modelNameTokens())
                .containsExactly("gpt", "5.4", "mini");
        assertThat(criteria.modelNameTokensJson())
                .isEqualTo("[\"gpt\",\"5.4\",\"mini\"]");
        assertThat(criteria.descriptionTokens())
                .containsExactly("5.4", "gpt", "mini");
        assertThat(criteria.descriptionTokensJson())
                .isEqualTo("[\"5.4\",\"gpt\",\"mini\"]");
    }

    @Test
    void removesEmptyAndDuplicateNameTokensWhileKeepingDots() {
        assertThat(service.modelNameTokensJson(" GPT--5.4-gpt-Mini "))
                .isEqualTo("[\"gpt\",\"5.4\",\"mini\"]");
    }

    @Test
    void writesDescriptionTokensWithTheExistingIkTokenizer() {
        when(descriptionTokenizer.tokenize("支持 GPT 模型"))
                .thenReturn(List.of("gpt", "支持", "模型"));

        assertThat(service.descriptionTokensJson("支持 GPT 模型"))
                .isEqualTo("[\"gpt\",\"支持\",\"模型\"]");
    }

    @Test
    void blankKeywordDisablesEverySearchBranch() {
        AiModelSearchCriteria criteria = service.prepare("   ");

        assertThat(criteria.hasKeyword()).isFalse();
        assertThat(criteria.vendorExact()).isNull();
        assertThat(criteria.modelNameTokens()).isEmpty();
        assertThat(criteria.modelNameTokensJson()).isNull();
        assertThat(criteria.descriptionTokens()).isEmpty();
        assertThat(criteria.descriptionTokensJson()).isNull();
    }

    @Test
    void emptyTokenListsNeverSerializeAsMatchAllQueryArrays() {
        when(descriptionTokenizer.tokenize("---")).thenReturn(List.of());

        AiModelSearchCriteria criteria = service.prepare("---");

        assertThat(criteria.modelNameTokensJson()).isNull();
        assertThat(criteria.descriptionTokensJson()).isNull();
        assertThat(criteria.vendorExact()).isEqualTo("---");
    }

    @Test
    void returnsDescriptionTokensOnlyWhenTheRowContainsEveryQueryToken() {
        when(descriptionTokenizer.tokenize("GPT Mini"))
                .thenReturn(List.of("gpt", "mini"));
        AiModelSearchCriteria criteria = service.prepare("GPT Mini");

        assertThat(service.matchedDescriptionTokens(
                "[\"gpt\",\"mini\",\"推理\"]",
                criteria))
                .containsExactly("gpt", "mini");
        assertThat(service.matchedDescriptionTokens(
                "[\"gpt\",\"推理\"]",
                criteria))
                .isEmpty();
    }

    @Test
    void returnsModelNameTokensOnlyWhenTheRowContainsEveryNameQueryToken() {
        when(descriptionTokenizer.tokenize("GPT Mini"))
                .thenReturn(List.of("gpt", "mini"));
        AiModelSearchCriteria criteria = service.prepare("GPT Mini");

        assertThat(service.matchedModelNameTokens(
                "[\"gpt\",\"5.4\",\"mini\"]",
                criteria))
                .containsExactly("gpt", "mini");
        assertThat(service.matchedModelNameTokens(
                "[\"gpt\",\"5.4\"]",
                criteria))
                .isEmpty();
    }

    @Test
    void rejectsCorruptedStoredDescriptionTokens() {
        when(descriptionTokenizer.tokenize("gpt")).thenReturn(List.of("gpt"));
        AiModelSearchCriteria criteria = service.prepare("gpt");

        assertThatThrownBy(() -> service.matchedDescriptionTokens(
                "[\"gpt\"",
                criteria))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("description tokens JSON");
        assertThatThrownBy(() -> service.matchedDescriptionTokens(null, criteria))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("description tokens JSON");
    }

    @Test
    void rejectsCorruptedStoredModelNameTokensWithFieldSpecificError() {
        when(descriptionTokenizer.tokenize("gpt")).thenReturn(List.of("gpt"));
        AiModelSearchCriteria criteria = service.prepare("gpt");

        assertThatThrownBy(() -> service.matchedModelNameTokens(
                "[\"gpt\"",
                criteria))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model name tokens JSON");
        assertThatThrownBy(() -> service.matchedModelNameTokens(null, criteria))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model name tokens JSON");
    }

    @Test
    void rejectsKeywordsLongerThanTheApiContract() {
        assertThatThrownBy(() -> service.prepare("a".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long");
    }
}
