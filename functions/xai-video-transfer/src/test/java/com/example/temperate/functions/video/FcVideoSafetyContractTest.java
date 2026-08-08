package com.example.temperate.functions.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 验证 FC 部署单元拒绝路径注入，并将源 GET、OSS SDK 与固定分片的无重试边界固化为源码契约。
 */
final class FcVideoSafetyContractTest {

    @Test
    void rejectsObjectKeysOutsideFixedPrefixOrWithTraversal() {
        assertEquals(
                "ai/video/2026/result.mp4",
                OssMultipartUploader.requireObjectKey(
                        "ai/video/2026/result.mp4", "ai/video/"));
        assertThrows(IllegalArgumentException.class, () ->
                OssMultipartUploader.requireObjectKey(
                        "other/result.mp4", "ai/video/"));
        assertThrows(IllegalArgumentException.class, () ->
                OssMultipartUploader.requireObjectKey(
                        "ai/video/../secret.mp4", "ai/video/"));
    }

    @Test
    void rejectsWeakDeploymentBoundary() {
        assertThrows(IllegalArgumentException.class, () ->
                new VideoTransferConfiguration(
                        "too-short",
                        "bucket",
                        "region",
                        "https://oss.example",
                        "ai/video/",
                        1024L,
                        Set.of("vidgen.x.ai")));
    }

    @Test
    void keepsGetMultipartAndPartBufferOnOneAttempt() throws IOException {
        String sourceClient = source("VideoSourceStream.java");
        String ossUploader = source("OssMultipartUploader.java");
        String relay = source("VideoStreamRelay.java");

        assertTrue(sourceClient.contains(
                "followRedirects(HttpClient.Redirect.NEVER)"));
        assertTrue(ossUploader.contains("retryMaxAttempts(1)"));
        assertTrue(relay.contains("8 * 1024 * 1024"));
        assertTrue(!sourceClient.contains(".retry("));
        assertTrue(!ossUploader.contains(".retry("));
    }

    @Test
    void doesNotUseCompletionAclOnInitiateMultipartBuilder() throws IOException {
        String uploader = source("OssMultipartUploader.java");
        int initiateStart = uploader.indexOf(
                "InitiateMultipartUploadRequest.newBuilder()");
        int initiateEnd = uploader.indexOf(".build()", initiateStart);

        assertTrue(initiateStart >= 0 && initiateEnd > initiateStart);
        // 当前 OSS SDK 的初始化 Builder 不提供 objectAcl；把完成阶段字段放到这里会直接阻断编译。
        assertFalse(uploader.substring(initiateStart, initiateEnd)
                .contains(".objectAcl("));
    }

    @Test
    void convertsSdkCloseExceptionToTransferIoException() throws IOException {
        String uploader = source("OssMultipartUploader.java");

        assertTrue(uploader.contains("public void close() throws IOException"));
        assertTrue(uploader.contains("catch (Exception exception)"));
    }

    @Test
    void obtainsTemporaryOssCredentialsOnlyFromFcExecutionContext()
            throws IOException {
        String handler = source("XaiVideoTransferHandler.java");
        String ossUploader = source("OssMultipartUploader.java");

        assertTrue(handler.contains("context.getExecutionCredentials()"));
        assertTrue(handler.contains("executionCredentials.getAccessKeyId()"));
        assertTrue(handler.contains("executionCredentials.getAccessKeySecret()"));
        assertTrue(handler.contains("executionCredentials.getSecurityToken()"));
        assertTrue(handler.contains("new StaticCredentialsProvider("));
        assertFalse(ossUploader.contains("EnvironmentVariableCredentialsProvider"));
    }

    @Test
    void targetsTheSupportedBuiltInJava11Runtime() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        String deployment = Files.readString(Path.of("s.yaml"));
        List<String> mainSources;
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            mainSources = paths.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                    })
                    .collect(java.util.stream.Collectors.toList());
        }

        assertTrue(pom.contains("<maven.compiler.release>11</maven.compiler.release>"));
        assertTrue(deployment.contains("runtime: java11"));
        assertTrue(deployment.contains("memorySize: 512"));
        assertTrue(deployment.contains("timeout: 900"));
        assertTrue(deployment.contains(
                "handler: com.example.temperate.functions.video."
                        + "XaiVideoTransferHandler::handleRequest"));
        assertFalse(mainSources.stream().anyMatch(source ->
                source.contains(" record ")
                        || source.contains("public record ")
                        || source.contains("private record ")
                        || source.contains("HexFormat")));
    }

    private static String source(String fileName) throws IOException {
        return Files.readString(Path.of(
                "src/main/java/com/example/temperate/functions/video",
                fileName));
    }
}
