package com.example.temperate.service.admin.mailinspection.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证邮箱检查创建命令使用防御性复制且调试文本永不暴露原始四段凭证。
 */
final class AdminMailInspectionCreateCommandTest {

    @Test
    void copiesInputAndRedactsToString() {
        List<String> source = new ArrayList<>();
        source.add("mail----password----client----refresh");

        AdminMailInspectionCreateCommand command =
                new AdminMailInspectionCreateCommand(source);
        source.clear();

        assertThat(command.credentialLines()).hasSize(1);
        assertThat(command.toString()).isEqualTo(
                "AdminMailInspectionCreateCommand[clientRequestId=protected,credentialLines=protected,count=1,businessConcurrency=4]");
        assertThat(command.toString()).doesNotContain("password", "refresh");
    }
}
