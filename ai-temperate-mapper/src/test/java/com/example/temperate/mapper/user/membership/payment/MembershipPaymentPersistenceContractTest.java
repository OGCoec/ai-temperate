package com.example.temperate.mapper.user.membership.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 该契约测试是来约束会员订单与支付回调的逻辑关联、单调版本和有界批量 SQL，不连接外部基础设施。
 */
final class MembershipPaymentPersistenceContractTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Test
    void schemaDefinesVersionedOrdersWithoutPhysicalForeignKeys() throws IOException {
        String orderSchema = read("sql/018_create_membership_order.sql");
        String callbackSchema = read("sql/019_create_membership_payment_callback.sql");
        String orphanChecks = read("sql/checks/membership_payment_orphans.sql");

        assertThat(orderSchema)
                .contains("payment_started_at TIMESTAMPTZ")
                .contains("closing_deadline_at TIMESTAMPTZ")
                .contains("state_version BIGINT NOT NULL DEFAULT 1")
                .contains("payment_started_at < expires_at")
                .contains("CHECK (state_version > 0)")
                .contains("UNIQUE (idempotency_key)")
                .contains("UNIQUE (provider_trade_no)")
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES");
        assertThat(callbackSchema)
                .contains("paid_at TIMESTAMPTZ NOT NULL")
                .contains("resolution VARCHAR(32)")
                .contains("resolved_at TIMESTAMPTZ")
                .contains("'APPLIED',")
                .contains("'ALREADY_APPLIED',")
                .contains("'REFUND_REQUIRED',")
                .contains("'REJECTED'")
                .contains("UNIQUE (order_id)")
                .contains("UNIQUE (provider_trade_no)")
                .doesNotContain("UNIQUE (provider_trade_no, trade_status)")
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES");
        assertThat(orphanChecks)
                .contains("LEFT JOIN userloginidentity")
                .contains("LEFT JOIN membership_order")
                .contains("MEMBERSHIP_ORDER_WITHOUT_LOGIN_IDENTITY")
                .contains("PAYMENT_CALLBACK_WITHOUT_ORDER");
    }

    @Test
    void mappersUseJsonbBatchingAndRejectStaleOrderVersions() throws IOException {
        String orderMapper = read(
                "ai-temperate-mapper/src/main/resources/mapper/user/membership/payment/"
                        + "MembershipOrderMapper.xml");
        String callbackMapper = read(
                "ai-temperate-mapper/src/main/resources/mapper/user/membership/payment/"
                        + "MembershipPaymentCallbackMapper.xml");

        assertThat(orderMapper)
                .contains("jsonb_array_elements_text")
                .contains("jsonb_to_recordset")
                .contains("payment_started_at")
                .contains("membership_order.state_version &lt; snapshots.state_version")
                .contains("DISTINCT ON (order_id)")
                .contains("FROM userloginidentity")
                .doesNotContain("${");
        assertThat(callbackMapper)
                .contains("jsonb_to_recordset")
                .contains("paid_at")
                .contains("resolution")
                .contains("resolved_at")
                .contains("ON CONFLICT DO NOTHING")
                .contains("INSERTED")
                .contains("DUPLICATE")
                .contains("ORDER_DUPLICATE")
                .contains("PROVIDER_TRADE_REUSED")
                .contains("IDENTITY_CONFLICT")
                .contains("same_callback")
                .doesNotContain("ON CONFLICT (provider_trade_no, trade_status)")
                .doesNotContain("${");
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(PROJECT_ROOT.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("sql"))
                    && Files.isDirectory(current.resolve("ai-temperate-mapper"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
