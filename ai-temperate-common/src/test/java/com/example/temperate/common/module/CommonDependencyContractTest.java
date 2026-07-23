package com.example.temperate.common.module;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 验证 common 模块只依赖允许的基础设施与第三方库，不反向引用上层业务模块。
 */
final class CommonDependencyContractTest {

    @Test
    void commonDoesNotDeclareUnusedHeavyDependencies() throws IOException {
        Path root = findProjectRoot();
        String commonPom = Files.readString(root.resolve("ai-temperate-common/pom.xml"));
        String parentPom = Files.readString(root.resolve("pom.xml"));

        assertNotDeclared(commonPom, "redisson");
        assertNotDeclared(commonPom, "gson");
        assertNotDeclared(commonPom, "libphonenumber");
        assertNotDeclared(commonPom, "ip2location-java");
        assertNotDeclared(commonPom, "ip2location-io-java");

        assertNotDeclared(parentPom, "redisson.version");
        // libphonenumber is version-managed in the parent because the service module now uses it
        // directly for registration E.164 normalization; common must still not declare it.
        assertNotDeclared(parentPom, "ip2location-io-java.version");
    }

    private static void assertNotDeclared(String pom, String dependencyName) {
        assertFalse(pom.contains(dependencyName),
                () -> "Unused dependency management remains for " + dependencyName);
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("ai-temperate-common"))
                    && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
