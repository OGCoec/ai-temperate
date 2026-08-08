package com.example.temperate.mapper.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.model.ai.entity.AiModelUsageVideoDetail;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证视频用量扩展表的实体、Mapper 与 XML 命名空间保持一致，防止 MyBatis 启动时因类型缺失而失败。
 */
final class AiModelUsageVideoDetailPersistenceContractTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Test
    void mapperXmlReferencesExistingEntityAndMapperContract() throws Exception {
        String mapperXml = Files.readString(
                PROJECT_ROOT.resolve(
                        "ai-temperate-mapper/src/main/resources/mapper/ai/"
                                + "AiModelUsageVideoDetailMapper.xml"),
                StandardCharsets.UTF_8);

        assertThat(Class.forName(
                        "com.example.temperate.model.ai.entity.AiModelUsageVideoDetail"))
                .isEqualTo(AiModelUsageVideoDetail.class);
        assertThat(AiModelUsageVideoDetailMapper.class.getMethod(
                        "insert", AiModelUsageVideoDetail.class))
                .isNotNull();
        assertThat(AiModelUsageVideoDetailMapper.class.getMethod(
                        "findByUsageId", byte[].class))
                .isNotNull();
        assertThat(mapperXml)
                .contains("namespace=\"com.example.temperate.mapper.ai."
                        + "AiModelUsageVideoDetailMapper\"")
                .contains("type=\"com.example.temperate.model.ai.entity."
                        + "AiModelUsageVideoDetail\"")
                .doesNotContain("${");
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("sql"))
                    && Files.isDirectory(current.resolve("ai-temperate-mapper"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
