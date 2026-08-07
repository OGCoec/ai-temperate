package com.example.temperate.service.user.aiconversation.context.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 静态验证上下文用量快照、压缩单飞和 SSE 竞态恢复的关键实现边界。
 */
final class AiConversationContextUsageContractTest {

    private static final Path ROOT = findProjectRoot();

    @Test
    void compactionClaimIsSingleFlightPerContextRevision() throws IOException {
        String claim = read(
                "ai-temperate-service/src/main/resources/lua/ai-conversation/claim_context_compaction.lua");

        assertThat(claim)
                .contains("contextRevision")
                .contains("QUEUED")
                .contains("RUNNING")
                .contains("operationPublicId")
                .contains("INCR")
                .contains("KEYS[2]")
                .contains("PEXPIRE");
    }

    @Test
    void newerUsageRevisionClearsOnlyStaleTerminalOperation()
            throws IOException {
        String usage = read(
                "ai-temperate-service/src/main/resources/lua/ai-conversation/publish_context_usage.lua");

        assertThat(usage)
                .contains("previousStatus == 'COMPLETED'")
                .contains("previousStatus == 'FAILED'")
                .contains("previousRevision ~= nextRevision")
                .contains("'status', 'IDLE'")
                .doesNotContain("previousStatus == 'RUNNING'");
    }

    @Test
    void contextObserverSubscribesBeforeReadingSnapshot() throws IOException {
        String observer = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/context/event/impl/AiConversationContextEventServiceImpl.java");

        int subscribe = observer.indexOf("eventSubscriber.subscribe(");
        int snapshot = observer.indexOf("usageService.getOwned(", subscribe);
        assertThat(subscribe).isGreaterThanOrEqualTo(0);
        assertThat(snapshot).isGreaterThan(subscribe);
        assertThat(observer)
                .contains("eventRevision")
                .contains("heartbeat")
                .contains("timeout")
                .contains("旧任务终态与新版本 queued 紧邻时")
                .contains("AiConversationCompactionStatus.QUEUED");
    }

    @Test
    void hardLimitWaitUsesEventsAndTimeoutWithoutSynchronousFallback()
            throws IOException {
        String coordinator = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/compaction/impl/AiConversationCompactionCoordinatorImpl.java");
        String response = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/impl/AiConversationResponseServiceImpl.java");

        assertThat(coordinator)
                .contains("eventSubscriber.subscribe(")
                .contains("properties.hardLimitWaitTimeout()")
                .contains("AI_CONTEXT_COMPACTION_TIMEOUT")
                .doesNotContain("Thread.sleep")
                .doesNotContain("while (!");
        assertThat(response)
                .contains("AiConversationCompactionTrigger.HARD_LIMIT_WAIT")
                .contains("Mono.fromCallable(() -> respond(command))")
                .contains("Schedulers.boundedElastic()")
                .doesNotContain("compactSynchronously");
    }

    @Test
    void asynchronousTaskFreezesBothDurableAndEphemeralCutoffs()
            throws IOException {
        String coordinator = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/compaction/impl/AiConversationCompactionCoordinatorImpl.java");

        assertThat(coordinator)
                .contains("safeCutoffMessageId")
                .contains("safeEphemeralOrdinal")
                .contains("frozenEphemeralRemainder")
                .contains("completed.latestPersistedMessageId()")
                .contains("latestIncludedEphemeralOrdinal(completed)");
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
