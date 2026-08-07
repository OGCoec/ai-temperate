package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 锁定多图编排的关键结构，防止后续修改退回整批普通 HTTP 或一个上游请求携带 n 大于 1。
 */
final class AiConversationImageMultiStreamContractTest {

    private static final Path ROOT = findProjectRoot();

    @Test
    void workerStartsOneIndependentStreamPerOutputAndMergesThem() throws Exception {
        String source = readJava(
                "generation/worker/impl/AiConversationGenerationWorkerImpl.java");

        assertThat(source)
                .contains("Flux.range(0, outputCount)")
                .contains(".flatMap(outputIndex ->")
                .contains("new AiConversationModelEvent.ImageFailure(")
                .contains("completed without a final image")
                .contains("finalSeen.get() && meteringSeen.get()")
                .contains("state.reserveFinalBytes(image)")
                .contains("maximumGeneratedImageBatchBytes")
                .contains("state.imageUsages.putIfAbsent(")
                .contains("state.imageUsages.remove(outputIndex)")
                .contains(".onErrorResume(")
                .contains("imageGeneration.outputCount()")
                .contains("concurrencyService.tryAcquire(");
    }

    @Test
    void runtimeLinkageFailureAbortsTheWholeBatchWithoutCatchingAllErrors()
            throws Exception {
        String strategy = readJava(
                "model/stream/impl/ImagesGenerationStreamingStrategy.java");
        String worker = readJava(
                "generation/worker/impl/AiConversationGenerationWorkerImpl.java");

        assertThat(strategy)
                .contains("catch (LinkageError failure)")
                .contains("runtimeFaultService.imageEventMappingFailure(")
                .doesNotContain("catch (Throwable");
        assertThat(worker)
                .contains("isRuntimeLinkageFailure(failure)")
                .contains("Flux.<AiConversationModelEvent>error(failure)")
                .contains("failUnfinishedImageOutputs(")
                .contains("state.removeFinalImage(outputIndex)")
                .contains("publishPreviewFailureSafely(")
                .contains("state.finished.countDown()")
                .contains("imagePreviewBroker.seal(generationPublicId)");
    }

    @Test
    void workerKeepsLeaseThroughOssAndSealsItsLocalPreviewBroker()
            throws Exception {
        String source = readJava(
                "generation/worker/impl/AiConversationGenerationWorkerImpl.java");

        assertThat(source)
                .contains("startLifecycleRenewal(lifecycle, lease, permit, state)")
                .contains("generatedMedia,\n                    remainingDuration(workerDeadlineNanos)")
                .contains("imagePreviewBroker.seal(generationPublicId)");
        assertThat(source.indexOf("startLifecycleRenewal(lifecycle, lease, permit, state)"))
                .isLessThan(source.indexOf("attachments.finalizeAttachments("));
    }

    @Test
    void newGenerationUsesInstanceWorkerQueueAndVersionedBillingQueue()
            throws Exception {
        String publisher = readJava(
                "generation/rabbit/impl/RabbitAiConversationGenerationEventPublisherImpl.java");
        String listeners = readJava(
                "generation/rabbit/impl/AiConversationGenerationRabbitListeners.java");

        assertThat(publisher)
                .contains("workerRoutingKeyV2(\n                        properties.instanceId())")
                .contains("TERMINAL_ROUTING_KEY_V2");
        assertThat(listeners)
                .contains("AiConversationGenerationRabbitNames.GENERATION_QUEUE")
                .contains("#{aiConversationGenerationWorkerV2Queue.name}")
                .contains("AiConversationGenerationRabbitNames.TERMINAL_QUEUE_V2");
    }

    @Test
    void weightedRedisPermitIsAllOrNothingAcrossAcquireRenewAndRelease()
            throws Exception {
        String acquire = readResource(
                "lua/ai-conversation/acquire_concurrency.lua");
        String renew = readResource(
                "lua/ai-conversation/renew_concurrency.lua");
        String release = readResource(
                "lua/ai-conversation/release_concurrency.lua");

        assertThat(acquire)
                .contains("ZCARD', KEYS[1]) + weight > globalLimit")
                .contains("ZCARD', KEYS[2]) + weight > userLimit")
                .contains("for index = 0, weight - 1 do");
        assertThat(renew).contains("for index = 0, weight - 1 do");
        assertThat(release).contains("for index = 0, weight - 1 do");
    }

    @Test
    void observerDisconnectKeepsReplaySlotsUntilBusinessTerminal()
            throws Exception {
        String source = readJava(
                "generation/observer/impl/"
                        + "AiConversationGenerationObserverServiceImpl.java");

        assertThat(source)
                .contains("if (terminalEvent(event.name())) {")
                .contains("releasePreviewSafely(generationPublicId)")
                .contains(".takeUntil(event -> terminalEvent(event.name()))");
        assertThat(source.indexOf("releasePreviewSafely(generationPublicId)"))
                .isLessThan(source.indexOf(".doFinally(ignored -> {"));
    }

    @Test
    void streamingStrategyChoosesGenerateOrEditEndpointFromFrozenAction()
            throws Exception {
        String source = readJava(
                "model/stream/impl/ImagesGenerationStreamingStrategy.java");

        assertThat(source)
                .contains("== AiConversationImageAction.EDIT")
                .contains("imageProperties.editsPath()")
                .contains("imageProperties.generationsPath()");
    }

    @Test
    void billedTerminalCarriesMessageAndRequestedCountForSlotRecovery()
            throws Exception {
        String source = readJava(
                "generation/billing/impl/"
                        + "AiConversationGenerationBillingConsumerImpl.java");

        assertThat(source)
                .contains("\"messagePublicId\", messageId > 0L")
                .contains("\"requestedImageCount\", requestedImageCount")
                .contains("inputSnapshot.imageGeneration().outputCount()")
                .contains("frozenPayload.getConversationMessageId()")
                .contains("frozenInput.imageGeneration().outputCount()")
                .contains("generatedAttachmentCodec.decode(")
                .contains("previewBroker.release(terminal.generationPublicId())");
    }

    private static String readJava(String relative) throws Exception {
        return Files.readString(ROOT.resolve(Path.of(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation",
                relative)), StandardCharsets.UTF_8);
    }

    private static String readResource(String relative) throws Exception {
        return Files.readString(ROOT.resolve(Path.of(
                        "ai-temperate-service/src/main/resources", relative)),
                StandardCharsets.UTF_8);
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
