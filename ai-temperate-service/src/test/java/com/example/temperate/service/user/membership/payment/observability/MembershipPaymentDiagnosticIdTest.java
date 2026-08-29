package com.example.temperate.service.user.membership.payment.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来证明日志订单摘要在内部字节和公共ID两种入口下保持一致，同时不会泄露原始资源ID。
 */
final class MembershipPaymentDiagnosticIdTest {

    @Test
    void producesSameDigestForInternalAndPublicOrderId() {
        byte[] internalId = new byte[16];
        Arrays.fill(internalId, (byte) 7);
        String publicId = new HybridBase64UrlCodec().encode(internalId);

        String fromBytes = MembershipPaymentDiagnosticId.orderRef(internalId);
        String fromPublicId = MembershipPaymentDiagnosticId.orderRef(publicId);

        assertThat(fromBytes)
                .isEqualTo(fromPublicId)
                .matches("^[A-Za-z0-9_-]{43}$")
                .doesNotContain(publicId);
    }

    @Test
    void rejectsMalformedPublicOrderId() {
        assertThatThrownBy(() -> MembershipPaymentDiagnosticId.orderRef("not-an-order"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
