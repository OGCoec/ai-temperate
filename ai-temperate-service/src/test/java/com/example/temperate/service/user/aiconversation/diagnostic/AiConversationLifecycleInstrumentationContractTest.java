package com.example.temperate.service.user.aiconversation.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 静态核对生命周期诊断覆盖关键异步与事务边界，并确保诊断代码没有引入轮询、消息队列或主动订阅。
 */
final class AiConversationLifecycleInstrumentationContractTest {

    private static final Path ROOT = findProjectRoot();

    @Test
    void responseFlowContainsTransportOwnershipAndFinalizerMilestones()
            throws IOException {
        String source = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/impl/AiConversationResponseServiceImpl.java");

        assertThat(source)
                .contains("\"REQUEST_RECEIVED\"")
                .contains("\"RESERVATION_COMPLETED\"")
                .contains("\"UPSTREAM_FIRST_CHUNK\"")
                .contains("\"SSE_FIRST_EVENT_READY\"")
                .contains("\"REACTOR_CANCEL_OBSERVED\"")
                .contains("\"TERMINAL_OWNERSHIP_CLAIMED\"")
                .contains("\"TERMINAL_OWNERSHIP_REJECTED\"")
                .contains("\"TERMINAL_POLICY_DECIDED\"")
                .contains("\"FINALIZER_SUBMITTED\"")
                .contains("\"FINALIZER_STARTED\"")
                .contains("\"FINALIZER_REJECTED_SYNC_FALLBACK\"")
                .doesNotContain("RabbitTemplate")
                .doesNotContain("@Scheduled(fixedDelay = 500")
                .doesNotContain(".subscribe()");
    }

    @Test
    void transactionCommitAndRollbackUseSynchronizationCallbacks()
            throws IOException {
        String source = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/billing/impl/AiConversationSettlementServiceImpl.java");
        int afterCommit = source.indexOf("public void afterCommit()");
        int committed = source.indexOf(
                "\"SETTLEMENT_TRANSACTION_COMMITTED\"", afterCommit);
        int afterCompletion = source.indexOf("public void afterCompletion(int status)");
        int rolledBack = source.indexOf(
                "\"SETTLEMENT_TRANSACTION_ROLLED_BACK\"", afterCompletion);

        assertThat(afterCommit).isGreaterThanOrEqualTo(0);
        assertThat(committed).isGreaterThan(afterCommit);
        assertThat(afterCompletion).isGreaterThan(committed);
        assertThat(rolledBack).isGreaterThan(afterCompletion);
        assertThat(source)
                .contains("\"QUOTA_UPDATE_COMPLETED\"")
                .contains("\"USAGE_UPDATE_COMPLETED\"")
                .contains("\"DETAIL_UPDATE_COMPLETED\"")
                .contains("\"SETTLEMENT_DB_WRITES_COMPLETED\"");
    }

    @Test
    void aspectPreservesLazyReactorExecution() throws IOException {
        String source = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/diagnostic/AiConversationLifecycleTimingAspect.java");

        assertThat(source)
                .contains("transformDeferred")
                .contains("doFinally")
                .doesNotContain(".subscribe(");
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
