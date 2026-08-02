package com.example.temperate.service.user.aiconversation.billing.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 静态验证 AI 会话预扣与结算源码保留幂等锁顺序、差额字段和提交后资料缓存失效边界。
 */
final class AiConversationBillingContractTest {

    private static final Path ROOT = findProjectRoot();

    @Test
    void advisoryLockPrecedesDuplicateLookupAndQuotaMutation() throws IOException {
        String billing = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/billing/impl/AiConversationBillingServiceImpl.java");

        int lock = billing.indexOf("acquireIdempotencyLock");
        int duplicate = billing.indexOf("findByIdempotencyDigest");
        int quotaLock = billing.indexOf("findByLoginIdentityIdForUpdate");
        assertThat(lock).isGreaterThanOrEqualTo(0);
        assertThat(duplicate).isGreaterThan(lock);
        assertThat(quotaLock).isGreaterThan(duplicate);
        assertThat(billing).contains(
                "cacheInvalidationExecutor.evictAfterCommit(command.userId())");
        assertThat(billing)
                .contains("quotaPlanService.getRequired(tier)")
                .doesNotContain("FREE_FULL_QUOTA_MINOR")
                .doesNotContain("FREE_PERIOD");
    }

    @Test
    void everyQuotaFinalizationInvalidatesProfileCacheAndUsesSettlementDelta()
            throws IOException {
        String settlement = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/billing/impl/AiConversationSettlementServiceImpl.java");

        assertThat(settlement)
                .contains("quotaSettlement.delta()")
                .contains("预扣后用户可能重新读取过余额")
                .doesNotContain("if (quotaSettlement.quotaChanged())")
                .doesNotContain("settleInterrupted(command)");
        assertThat(settlement.split(
                "cacheInvalidationExecutor.evictAfterCommit", -1))
                .hasSizeGreaterThanOrEqualTo(4);
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
