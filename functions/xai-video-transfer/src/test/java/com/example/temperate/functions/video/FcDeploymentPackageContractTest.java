package com.example.temperate.functions.video;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * 该测试用于约束 FC Web Function 的部署包必须自带 Linux Java 11 运行环境，并避免重新退回依赖系统 PATH 的启动方式。
 */
final class FcDeploymentPackageContractTest {

    @Test
    void bootstrapUsesBundledLinuxJavaRuntime() throws IOException {
        String bootstrap = Files.readString(projectFile("bootstrap"), StandardCharsets.UTF_8);

        assertTrue(bootstrap.contains("OpenJDK11U-jre_x64_linux_hotspot_11.0.28_6.tar.gz"));
        assertTrue(bootstrap.contains("/tmp/xai-video-transfer-java11/bin/java"));
        assertFalse(bootstrap.contains("exec java "));
    }

    @Test
    void serverlessDeploymentUsesGeneratedFcDirectory() throws IOException {
        String serverlessConfiguration = Files.readString(projectFile("s.yaml"), StandardCharsets.UTF_8);

        assertTrue(serverlessConfiguration.contains("code: ./target/fc-deploy"));
        assertFalse(serverlessConfiguration.contains("code: ./target\n"));
    }

    private static Path projectFile(String fileName) {
        Path currentDirectory = Path.of("").toAbsolutePath();
        Path directFile = currentDirectory.resolve(fileName);
        if (Files.exists(directFile)) {
            return directFile;
        }
        return currentDirectory.resolve("functions/xai-video-transfer").resolve(fileName);
    }
}
