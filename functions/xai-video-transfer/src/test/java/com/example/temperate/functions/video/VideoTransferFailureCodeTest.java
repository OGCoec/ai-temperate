package com.example.temperate.functions.video;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证 FC 传输失败只输出阶段白名单码，不把源地址、凭据或底层异常消息带出函数边界。
 */
final class VideoTransferFailureCodeTest {

    @Test
    void exposesSafeStageCodesAndNeverSerializesFailureMessage()
            throws IOException {
        String server = Files.readString(Path.of(
                "src/main/java/com/example/temperate/functions/video",
                "XaiVideoTransferWebServer.java"));
        assertTrue(server.contains("SOURCE_OPEN_FAILED"));
        assertTrue(server.contains("OSS_CREDENTIALS_UNAVAILABLE"));
        assertTrue(server.contains("OSS_MULTIPART_INIT_FAILED"));
        assertTrue(server.contains("VideoTransferFailureCode.safeCode"));
        assertFalse(server.contains("writer.failed(sequence.incrementAndGet(),\n"
                + "                        exception.getMessage()"));
    }
}
