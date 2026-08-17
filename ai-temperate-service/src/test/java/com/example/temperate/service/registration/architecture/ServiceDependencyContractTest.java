package com.example.temperate.service.registration.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证 Service 模块依赖方向与接口实现分离约束的架构测试。
 */
class ServiceDependencyContractTest {

    @Test
    void registrationDeclaresItsModelAndTransactionDependenciesDirectly() throws IOException {
        String servicePom = Files.readString(
                findProjectRoot().resolve("ai-temperate-service/pom.xml"));

        assertThat(servicePom)
                .contains("<artifactId>ai-temperate-model</artifactId>")
                .contains("<artifactId>spring-tx</artifactId>")
                .contains("<artifactId>micrometer-core</artifactId>")
                .contains("<artifactId>jjwt-api</artifactId>")
                .contains("<artifactId>ip2location-java</artifactId>")
                .contains("<artifactId>redisson</artifactId>")
                .contains("<artifactId>jakarta.annotation-api</artifactId>");
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("ai-temperate-service"))
                    && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
