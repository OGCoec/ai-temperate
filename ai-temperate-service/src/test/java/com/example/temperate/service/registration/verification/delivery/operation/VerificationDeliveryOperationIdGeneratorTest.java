package com.example.temperate.service.registration.verification.delivery.operation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 验证验证码投递操作编号生成器必须产生固定长度、URL 安全且足够分散的随机值。
 */
class VerificationDeliveryOperationIdGeneratorTest {

    @Test
    void generatesThirtyEightCharacterUrlSafeNanoIds() {
        VerificationDeliveryOperationIdGenerator generator =
                new VerificationDeliveryOperationIdGenerator();

        Set<String> generated = new HashSet<>();
        for (int index = 0; index < 256; index++) {
            String operationId = generator.generateRawOperationId();

            assertThat(operationId).matches("^[A-Za-z0-9_-]{38}$");
            generated.add(operationId);
        }
        assertThat(generated).hasSize(256);
    }
}
