package com.example.temperate.service.user.aiconversation.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 静态验证最终 Usage 优先级、中断有界执行和有限结算重试没有被后续改动绕过。
 */
final class AiConversationResponseFlowContractTest {

    private static final Path ROOT = findProjectRoot();

    @Test
    void finalUsageOwnershipPrecedesInterruptedFailureSettlement()
            throws IOException {
        String source = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/impl/AiConversationResponseServiceImpl.java");

        int successOwnership = source.indexOf(
                "== AiConversationRequestState.FINALIZING_SUCCESS",
                source.indexOf("private Mono<AiConversationStreamEvent> finalizeFailure"));
        int interruptedOwnership = source.indexOf(
                "tryBeginInterruptedFinalization()", successOwnership);
        assertThat(successOwnership).isGreaterThanOrEqualTo(0);
        assertThat(interruptedOwnership).isGreaterThan(successOwnership);
        assertThat(source)
                .contains("return successFinalization(")
                .contains("CompletableFuture.supplyAsync")
                .contains("finalizerExecutor")
                .contains("Mono.fromFuture(future, true)")
                .contains("Mono.fromCallable(() -> lifecycleDiagnosticService.withContext(")
                .contains("return completeAndRelease(")
                .contains("generatedMediaRejected")
                .contains("chunk.generatedMediaTruncated()")
                .contains("state.candidateUsage.set(chunk.usage())")
                .contains("state.terminalFinishObserved.set(true)")
                .contains("terminalObservedBefore || terminalInCurrentChunk")
                .contains("state.usage.set(chunk.usage())")
                .contains("generationResourcesReleased.compareAndSet(false, true)")
                .contains("attachmentProperties.maxFilesPerMessage()")
                .contains("attachmentProperties.maxTotalBytesPerMessage()")
                .contains("AiConversationActivityPhase.FINALIZING")
                .doesNotContain("settlementService.settleInterrupted(command).subscribe");
    }

    @Test
    void synchronousStreamFailureSettlementHasBoundedAttempts()
            throws IOException {
        String source = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/impl/AiConversationResponseServiceImpl.java");

        assertThat(source)
                .contains("MAX_INTERRUPTION_FINALIZATION_ATTEMPTS = 3")
                .contains("attempt <= MAX_INTERRUPTION_FINALIZATION_ATTEMPTS")
                .contains("terminalBillingPolicy.systemFailure(failure)")
                .contains("AiConversationTerminalBillingAction.REFUND_FULL")
                .contains("lifecycle.markReconcileRequired()")
                .contains("diagnosticRecorded.compareAndSet(false, true)")
                .contains("failureDiagnosticService.diagnose(")
                .contains("buildTerminalErrorEvent(");
    }

    @Test
    void directResponseMapsEveryStandardEventWithoutRedisBatching() throws IOException {
        String source = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/impl/AiConversationResponseServiceImpl.java");

        assertThat(source)
                .contains("observeBoundary(\n                        upstream,")
                .contains(".concatMapIterable(event -> processModelEvent(event, state))")
                .contains("AiConversationStreamingProtocol.CHAT_COMPLETIONS")
                .contains(".RESPONSES_WEB_SEARCH")
                .contains("AiConversationStreamEvent.activity(")
                .contains("AiConversationStreamEvent.source(")
                .contains("AiConversationStreamEvent.reasoningSummary(")
                .contains("HEARTBEAT_INTERVAL = Duration.ofSeconds(15)")
                .contains("contextStore.saveInterruptedTurn(")
                .contains("AtomicReference<Subscription> responseSubscription")
                .contains("responseSubscription.set(subscription)")
                .doesNotContain("AiConversationStreamBatcher")
                .doesNotContain("persistRedisBatch(")
                .doesNotContain("appendAssistantChunks(");
    }

    @Test
    void streamBatcherPreservesNativeBackpressureWithoutManualSubscription()
            throws IOException {
        String source = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/AiConversationStreamBatcher.java");

        assertThat(source)
                .contains("extends FluxOperator<")
                .contains("actual.onNext(chunk);")
                .contains("upstream.request(n);")
                .contains("upstream.cancel();")
                .doesNotContain("Flux.create(")
                .doesNotContain("FluxSink")
                .doesNotContain("onBackpressureBuffer(")
                .doesNotContain("request(Long.MAX_VALUE)");
    }

    @Test
    void heartbeatFailuresEnterTheSameTerminalRefundChain() throws IOException {
        String source = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/impl/AiConversationResponseServiceImpl.java");
        int heartbeat = source.indexOf(
                "Flux<AiConversationStreamEvent> withHeartbeat = completed.publish(");
        int terminalHandler = source.indexOf(
                ".onErrorResume(failure -> finalizeFailure(", heartbeat);

        assertThat(heartbeat).isGreaterThanOrEqualTo(0);
        assertThat(terminalHandler).isGreaterThan(heartbeat);
        assertThat(source.substring(heartbeat, terminalHandler))
                .contains("leaseService.renew(lease)")
                .contains("concurrencyService.renew(")
                .contains("AI_CONTEXT_CACHE_UNAVAILABLE");
    }

    @Test
    void selectedReasoningEffortReachesTheUpstreamModelRequest()
            throws IOException {
        String source = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/impl/AiConversationResponseServiceImpl.java");

        assertThat(source)
                .contains("command.reasoningEffort()")
                .contains("new AiConversationModelRequest(");
    }

    @Test
    void settledIdempotentReplayRestoresPersistedAnswerBeforeCompleted()
            throws IOException {
        String source = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/impl/AiConversationResponseServiceImpl.java");
        int replayStart = source.indexOf("private AiConversationResponseStream replay(");
        int replayEnd = source.indexOf("private List<AiConversationAttachment> persistedAttachments", replayStart);
        String replay = source.substring(replayStart, replayEnd);

        assertThat(replay)
                .contains("message.getQuestionTokens()")
                .contains("AiConversationStreamEvent.delta(")
                .contains("\"TEXT\"")
                .contains("AiConversationStreamEvent.completed(");
        assertThat(replay.indexOf("AiConversationStreamEvent.delta("))
                .isLessThan(replay.indexOf("AiConversationStreamEvent.completed("));
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
