package com.example.temperate.mapper.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 验证模型发现只提供一次有界名称集合查询，SQL 与数据库规范化表达式保持一致。
 */
final class AiModelDiscoveryMapperContractTest {

    @Test
    void exposesOneBatchMethodAndUsesNormalizedNameExpression() throws IOException {
        assertThat(AiModelMapper.class.getDeclaredMethods())
                .anySatisfy(method -> {
                    assertThat(method.getName()).isEqualTo("findByNormalizedModelNames");
                    assertThat(method.getParameterCount()).isEqualTo(1);
                });

        try (var stream = getClass().getResourceAsStream(
                "/mapper/ai/AiModelMapper.xml")) {
            assertThat(stream).isNotNull();
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(xml).contains(
                    "<select id=\"findByNormalizedModelNames\"",
                    "resultMap=\"AiModelDiscoveryResultMap\"",
                    "model.cached_input_ratio",
                    "WHERE model.model_name IN",
                    "<foreach collection=\"modelNames\"");
            int queryStart = xml.indexOf(
                    "<select id=\"findByNormalizedModelNames\"");
            int queryEnd = xml.indexOf("</select>", queryStart);
            String discoveryQuery = xml.substring(queryStart, queryEnd);
            assertThat(discoveryQuery)
                    .doesNotContain("LEFT JOIN", "description", "tokens_json", "tags_json");
        }
    }
}
