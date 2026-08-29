package com.example.temperate.service.user.membership.payment.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 该静态测试是来禁止会员支付业务绕过统一 UTC 微秒时钟，避免新路径再次把订单或回调事实降为毫秒。
 */
final class MembershipPaymentTimeArchitectureTest {

    @Test
    void membershipPaymentSourcesUseTheUnifiedMicrosecondClock() throws IOException {
        Path sourceRoot = findProjectRoot().resolve(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/"
                        + "membership/payment");
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            String source = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith("MembershipPaymentTime.java"))
                    .map(MembershipPaymentTimeArchitectureTest::readUnchecked)
                    .reduce("", String::concat);
            assertThat(source)
                    .doesNotContain("OffsetDateTime.ofInstant(")
                    .doesNotContain("OffsetDateTime.now(clock)");
        }
    }

    private static String readUnchecked(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError("Could not read " + path, exception);
        }
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
