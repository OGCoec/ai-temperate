package com.example.temperate.web.user.aiconversation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 静态验证会话历史与附件预上传 Controller 保持公共 ID、无缓存和受保护 API 边界。
 */
final class AiConversationReadApiContractTest {

    private static final Path ROOT = findProjectRoot();

    @Test
    void queryControllerRemainsProxyableForMethodValidation() {
        assertThat(Modifier.isFinal(AiConversationQueryController.class.getModifiers()))
                .isFalse();
    }

    @Test
    void historyApiIsReadOnlyNoStoreAndDoesNotAcceptUserIdentifiers()
            throws IOException {
        String source = read(
                "ai-temperate-web/src/main/java/com/example/temperate/web/user/aiconversation/controller/AiConversationQueryController.java");

        assertThat(source)
                .contains("@RequestMapping(\"/api/ai/conversations\")")
                .contains("@GetMapping")
                .contains("@GetMapping(\"/{conversationPublicId}/messages\")")
                .contains("@AuthenticationPrincipal SessionPrincipal principal")
                .contains("CacheControl.noStore().cachePrivate()")
                .contains("AiConversationPublicId conversationPublicId")
                .doesNotContain("@RequestBody")
                .doesNotContain("userId,");
    }

    @Test
    void preuploadApiAcceptsOnlyFileMetadataAndNeverExposesObjectKeys()
            throws IOException {
        String controller = read(
                "ai-temperate-web/src/main/java/com/example/temperate/web/user/aiconversation/controller/AiConversationAttachmentController.java");
        String request = read(
                "ai-temperate-web/src/main/java/com/example/temperate/web/user/aiconversation/api/AiConversationPreuploadFileRequest.java");
        String response = read(
                "ai-temperate-web/src/main/java/com/example/temperate/web/user/aiconversation/api/AiConversationPreuploadFileResponse.java");

        assertThat(controller)
                .contains("@PostMapping(\"/preuploads\")")
                .contains("HttpStatus.CREATED")
                .contains("CacheControl.noStore().cachePrivate()");
        assertThat(request)
                .contains("String fileName")
                .contains("String contentType")
                .contains("String sizeBytes")
                .doesNotContain("bucket")
                .doesNotContain("objectKey")
                .doesNotContain("userId");
        assertThat(response)
                .contains("String uploadUrl")
                .contains("Map<String, String> uploadHeaders")
                .doesNotContain("objectKey")
                .doesNotContain("accessKey");
    }

    @Test
    void sseInputUsesPreuploadReferencesInsteadOfClientSuppliedUrls()
            throws IOException {
        String input = read(
                "ai-temperate-web/src/main/java/com/example/temperate/web/user/aiconversation/api/AiConversationInputAttachmentRequest.java");
        String controller = read(
                "ai-temperate-web/src/main/java/com/example/temperate/web/user/aiconversation/controller/AiConversationResponseController.java");

        assertThat(input)
                .contains("String uploadSessionId")
                .contains("String attachmentId")
                .contains("String sizeBytes")
                .doesNotContain("url")
                .doesNotContain("photoUrls");
        assertThat(controller)
                .contains("AiConversationAttachmentUploadReference")
                .contains("private, no-store, no-transform")
                .contains("X-Accel-Buffering")
                .doesNotContain("photoUrls");
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(ROOT.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("sql"))
                    && Files.isDirectory(current.resolve("ai-temperate-web"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
