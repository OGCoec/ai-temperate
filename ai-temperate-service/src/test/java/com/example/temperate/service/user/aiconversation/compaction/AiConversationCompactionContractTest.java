package com.example.temperate.service.user.aiconversation.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 静态验证两层压缩固定截止点、数据库 CAS 和 Redis 选择性替换的并发安全边界。
 */
final class AiConversationCompactionContractTest {

    private static final Path ROOT = findProjectRoot();

    @Test
    void durableCompactionUsesCapturedCutoffAndNeverUnlinksWholeContext()
            throws IOException {
        String compaction = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/compaction/impl/AiConversationCompactionServiceImpl.java");

        assertThat(compaction)
                .contains("long cutoffMessageId")
                .contains("findCompactionRange(")
                .contains("persistenceService.compareAndSet(")
                .contains("replaceDurableCompaction(")
                .contains("for (int attempt = 0; attempt < 3; attempt++)")
                .doesNotContain("contextStore.invalidate(")
                .doesNotContain("contextStore.unlink(");
    }

    @Test
    void ephemeralCompactionDeletesOnlySelectedInterruptedOrdinals()
            throws IOException {
        String compaction = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/compaction/impl/AiConversationCompactionServiceImpl.java");
        String store = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/context/impl/RedisAiConversationContextStore.java");

        assertThat(compaction)
                .contains("AiConversationTurnState.INTERRUPTED")
                .contains("throughEphemeralOrdinal")
                .contains("replaceEphemeralCompaction(");
        assertThat(store)
                .contains("Set.copyOf(compactedEphemeralOrdinals)")
                .contains("selectedOrdinals.contains(ephemeralOrdinal(field))");
    }

    @Test
    void emergencyBudgetPathCompressesEphemeralBeforeDurable()
            throws IOException {
        String response = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/impl/AiConversationResponseServiceImpl.java");

        int ephemeral = response.indexOf("compactEphemeralSynchronously(");
        int durable = response.indexOf("compactSynchronously(", ephemeral);
        assertThat(ephemeral).isGreaterThanOrEqualTo(0);
        assertThat(durable).isGreaterThan(ephemeral);
    }

    @Test
    void everyCompactionTaskSelectsOneEnabledModelBeforeProcessing()
            throws IOException {
        String compaction = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/compaction/impl/AiConversationCompactionServiceImpl.java");

        int durableSelection = compaction.indexOf(
                "modelSelector.selectRequired(conversationPublicId)");
        int durableLoop = compaction.indexOf("while (cursor < cutoff)");
        int ephemeralSelection = compaction.indexOf(
                "modelSelector.selectRequired(conversationPublicId)",
                durableSelection + 1);

        assertThat(durableSelection).isGreaterThanOrEqualTo(0);
        assertThat(durableSelection).isLessThan(durableLoop);
        assertThat(ephemeralSelection).isGreaterThan(durableSelection);
        assertThat(compaction)
                .contains("selectedModel.modelName()")
                .doesNotContain("inferenceProperties.compactionModel()")
                .doesNotContain("AiInferenceProperties inferenceProperties");
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(ROOT.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("sql"))
                    && Files.isDirectory(current.resolve("ai-temperate-service"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
