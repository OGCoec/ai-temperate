package com.example.temperate.service.admin.mailinspection.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 验证邮箱检查策略 Registry 的正确选择、重复类型启动失败和未知类型受控失败。
 */
final class MailInspectionStrategyRegistryTest {

    @Test
    void selectsStrategyByStableEnumType() {
        MailInspectionStrategy openAi = strategy(MailInspectionType.OPENAI_STATUS);
        MailInspectionStrategyRegistry registry =
                new MailInspectionStrategyRegistry(Map.of("openAi", openAi));

        assertThat(registry.getRequired(MailInspectionType.OPENAI_STATUS))
                .isSameAs(openAi);
    }

    @Test
    void rejectsDuplicateStrategyTypeAtConstruction() {
        assertThatThrownBy(() -> new MailInspectionStrategyRegistry(Map.of(
                        "first", strategy(MailInspectionType.OPENAI_STATUS),
                        "second", strategy(MailInspectionType.OPENAI_STATUS))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsUnknownTypeWithControlledAdminException() {
        MailInspectionStrategyRegistry registry =
                new MailInspectionStrategyRegistry(Map.of());

        assertThatThrownBy(() -> registry.getRequired(MailInspectionType.KIRO_STATUS))
                .isInstanceOf(AdminException.class);
    }

    private static MailInspectionStrategy strategy(MailInspectionType type) {
        return new MailInspectionStrategy() {
            @Override
            public MailInspectionType type() {
                return type;
            }

            @Override
            public Mono<com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult>
                    inspect(MailboxCredential credential) {
                return Mono.empty();
            }
        };
    }
}
