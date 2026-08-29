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
        String entitlementMigration = read(
                "sql/migrations/030_add_membership_order_entitlement_resolution.sql");
        String activeOrderIndexMigration = read(
                "sql/migrations/031_create_membership_order_single_active_index.sql");
        String terminalEntitlementMigration = read(
                "sql/migrations/032_add_membership_order_not_granted_resolution.sql");
        String orphanChecks = read("sql/checks/membership_payment_orphans.sql");

        assertThat(orderSchema)
                .contains("payment_started_at TIMESTAMPTZ(6)")
                .contains("expires_at TIMESTAMPTZ(6) NOT NULL")
                .contains("closing_deadline_at TIMESTAMPTZ(6)")
                .contains("paid_at TIMESTAMPTZ(6)")
                .contains("state_version BIGINT NOT NULL DEFAULT 1")
                .contains("payment_started_at < expires_at")
                .contains("CHECK (state_version > 0)")
                .contains("UNIQUE (idempotency_key)")
                .contains("UNIQUE (provider_trade_no)")
                .contains("entitlement_resolution VARCHAR(32)")
                .contains("entitlement_resolved_at TIMESTAMPTZ(6)")
                .contains("created_at TIMESTAMPTZ(6) NOT NULL")
                .contains("updated_at TIMESTAMPTZ(6) NOT NULL")
                .contains("'NOT_GRANTED'")
                .contains("'LEGACY_NOT_GRANTED'")
                .contains("status IN (0, 1)")
                .contains("status = 2")
                .contains("entitlement_resolution IS NULL")
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES");
        assertThat(entitlementMigration)
                .contains("LEGACY_NOT_GRANTED")
                .contains("entitlement_resolution IS NULL")
                .contains("RAISE EXCEPTION")
                .doesNotContain("DELETE FROM membership_order")
                .doesNotContain("UPDATE membership_order\nSET status");
        assertThat(activeOrderIndexMigration)
                .contains("CREATE UNIQUE INDEX CONCURRENTLY")
                .contains("status IN (0, 1)")
                .contains("status = 2")
                .contains("entitlement_resolution IS NULL")
                .doesNotContain("BEGIN;")
                .doesNotContain("DO $$")
                .doesNotContain("COMMENT ON INDEX");
        assertThat(terminalEntitlementMigration)
                .contains("'NOT_GRANTED'")
                .contains("'REFUND_REQUIRED'")
                .contains("status IN (3, 4)")
                .contains("paid_at IS NULL")
                .contains("entitlement_resolution IS NULL")
                .doesNotContain("DELETE FROM membership_order");
        assertThat(callbackSchema)
                .contains("paid_at TIMESTAMPTZ(6) NOT NULL")
                .contains("received_at TIMESTAMPTZ(6) NOT NULL")
                .contains("resolution VARCHAR(32)")
                .contains("resolved_at TIMESTAMPTZ(6)")
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
                .contains("pg_advisory_xact_lock")
                .contains("findActiveByLoginIdentityId")
                .contains("findByIdsJsonForUpdate")
                .contains("batchResolveEntitlements")
                .contains("provider_trade_no = CASE")
                .contains("entitlements.resolution IN ('REFUND_REQUIRED', 'NOT_GRANTED')")
                .contains("membership_order.provider_trade_no = entitlements.provider_trade_no")
                .contains("'NOT_GRANTED'")
                .contains("entitlements.resolution = 'REFUND_REQUIRED'")
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
                .contains("findByIdsJsonForUpdate")
                .doesNotContain("ON CONFLICT (provider_trade_no, trade_status)")
                .doesNotContain("${");
    }

    @Test
    void callbackConflictResolutionUsesBoundedIndexedJoinsInsteadOfFullFactMaterialization()
            throws IOException {
        String callbackMapper = read(
                "ai-temperate-mapper/src/main/resources/mapper/user/membership/payment/"
                        + "MembershipPaymentCallbackMapper.xml");

        assertThat(callbackMapper)
                .doesNotContain("callback_facts AS")
                .contains("affectData=\"true\"")
                .contains("flushCache=\"true\"")
                .contains("useCache=\"false\"")
                .contains("LEFT JOIN inserted inserted_by_callback_id")
                .contains("LEFT JOIN inserted inserted_by_order_id")
                .contains("LEFT JOIN inserted inserted_by_provider_trade_no")
                .contains("LEFT JOIN membership_payment_callback stored_by_callback_id")
                .contains("LEFT JOIN membership_payment_callback stored_by_order_id")
                .contains("LEFT JOIN membership_payment_callback stored_by_provider_trade_no");
    }

    @Test
    void orderCreationUsesOneLockedCteStatementAndKeepsIndexedFallbacks()
            throws IOException {
        String orderMapper = read(
                "ai-temperate-mapper/src/main/resources/mapper/user/membership/payment/"
                        + "MembershipOrderMapper.xml");

        assertThat(orderMapper)
                .contains("<select id=\"createOrResolve\"")
                .contains("affectData=\"true\"")
                .contains("flushCache=\"true\"")
                .contains("useCache=\"false\"")
                .contains("creation_lock AS MATERIALIZED")
                .contains("existing_by_idempotency AS MATERIALIZED")
                .contains("active_order AS MATERIALIZED")
                .contains("inserted AS")
                .contains("resolved AS")
                .contains("ON CONFLICT DO NOTHING")
                .contains("ORDER BY source_priority")
                .doesNotContain("WHERE idempotency_key = #{idempotencyKey} OR");
    }

    @Test
    void latestPaidLookupUsesAStablePartialIndexContract() throws IOException {
        String orderSchema = read("sql/018_create_membership_order.sql");
        String orderMapper = read(
                "ai-temperate-mapper/src/main/resources/mapper/user/membership/payment/"
                        + "MembershipOrderMapper.xml");
        Path migrationPath = PROJECT_ROOT.resolve(
                "sql/migrations/034_create_membership_order_latest_paid_index.sql");

        assertThat(migrationPath).exists();
        String migration = Files.readString(migrationPath, StandardCharsets.UTF_8);

        assertThat(orderMapper)
                .contains("<select id=\"findLatestPaidOrder\"")
                .contains("AND status = 2")
                .contains("ORDER BY paid_at DESC NULLS LAST, created_at DESC, id DESC")
                .doesNotContain("#{paidStatus");
        assertThat(orderSchema)
                .contains("idx_membership_order_latest_paid")
                .contains("paid_at DESC NULLS LAST")
                .contains("WHERE status = 2");
        assertThat(migration)
                .contains("CREATE INDEX CONCURRENTLY IF NOT EXISTS")
                .contains("idx_membership_order_latest_paid")
                .contains("login_identity_id")
                .contains("membership_tier")
                .contains("paid_at DESC NULLS LAST")
                .contains("created_at DESC")
                .contains("id DESC")
                .contains("WHERE status = 2")
                .doesNotContain("BEGIN;")
                .doesNotContain("COMMIT;");
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
