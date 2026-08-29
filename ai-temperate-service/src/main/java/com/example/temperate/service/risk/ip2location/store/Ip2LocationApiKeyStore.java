package com.example.temperate.service.risk.ip2location.store;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.ip2location.domain.Ip2LocationImportMode;
import com.example.temperate.service.risk.ip2location.domain.ProtectedIp2LocationKey;
import java.util.List;
import java.util.Optional;

/**
 * 该接口是来定义 IP2Location 两个 Redis Hash 的有界写入、随机扣减、分页读取和删除边界。
 */
public interface Ip2LocationApiKeyStore {

    BatchWriteResult writeBatch(
            List<ProtectedIp2LocationKey> keys,
            long initialQuota,
            Ip2LocationImportMode mode);

    Optional<AcquiredEnvelope> acquire();

    EncryptedPage scan(long cursor, int size);

    long delete(List<HmacIdentifier> keyIds);

    record BatchWriteResult(
            int createdCount,
            int updatedCount,
            int duplicateCount,
            int capacityRejectedCount) {
    }

    record AcquiredEnvelope(
            HmacIdentifier keyId,
            String encryptedEnvelope,
            long remainingQuota) {

        @Override
        public String toString() {
            return "AcquiredEnvelope[redacted]";
        }
    }

    record EncryptedEntry(
            HmacIdentifier keyId,
            String encryptedEnvelope,
            long remainingQuota) {

        @Override
        public String toString() {
            return "EncryptedEntry[redacted]";
        }
    }

    record EncryptedPage(
            long nextCursor,
            List<EncryptedEntry> entries) {
    }
}
