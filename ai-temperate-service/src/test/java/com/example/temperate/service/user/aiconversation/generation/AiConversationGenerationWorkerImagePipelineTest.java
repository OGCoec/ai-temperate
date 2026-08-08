package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 锁定多图 Worker 的单槽位就绪上传结构，防止上传重新退化为等待整批生成结束后才开始。
 */
final class AiConversationGenerationWorkerImagePipelineTest {

    private static final Path ROOT = findProjectRoot();

    @Test
    void completedChildSubmitsItsUploadBeforeTheWholeImageFluxFinishes()
            throws Exception {
        String events = readJava("model/stream/AiConversationModelEvent.java");
        String worker = readJava(
                "generation/worker/impl/AiConversationGenerationWorkerImpl.java");
        String compactWorker = worker.replaceAll("\\s+", "");

        assertThat(events).contains("record ImageOutputReady(short outputIndex)");
        assertThat(compactWorker)
                .contains("newAiConversationModelEvent.ImageOutputReady(childIndex)");
        assertThat(worker)
                .contains("finalSeen.get() && meteringSeen.get()")
                .contains("openGeneratedUploadSession(")
                .contains("state.imageUploadTasks.putIfAbsent(")
                .contains(".submit(outputIndex, media)")
                .contains("uploadSession.finish(")
                .contains("state.imageUsages.remove(outputIndex)")
                .contains("state.imageMeteringEvidence.remove(outputIndex)")
                .doesNotContain("attachments.finalizeAttachments(");
        assertThat(worker.indexOf(".submit(outputIndex, media)"))
                .isLessThan(worker.indexOf("uploadSession.finish("));
    }

    @Test
    void nonSuccessfulWorkerExitKeepsAnIdempotentCompensationBackstop()
            throws Exception {
        String worker = readJava(
                "generation/worker/impl/AiConversationGenerationWorkerImpl.java");

        assertThat(worker)
                .contains("abortImageUploadsSafely(state)")
                .contains("uploadSession.commit()")
                .contains("imagePreviewBroker.publishPersisted(");
        assertThat(worker.indexOf("imagePreviewBroker.publishPersisted("))
                .isLessThan(worker.indexOf("private void freezeCompletedImage("));
        String terminalSection = worker.substring(
                worker.indexOf("private void freezeCompletedImage("));
        assertThat(terminalSection.indexOf("awaitImageUploadTasks("))
                .isLessThan(terminalSection.indexOf("terminalService.freeze("));
        assertThat(terminalSection.indexOf("terminalService.freeze("))
                .isLessThan(terminalSection.indexOf("uploadSession.commit()"));
    }

    private static String readJava(String relative) throws Exception {
        return Files.readString(ROOT.resolve(Path.of(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation",
                relative)), StandardCharsets.UTF_8);
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
