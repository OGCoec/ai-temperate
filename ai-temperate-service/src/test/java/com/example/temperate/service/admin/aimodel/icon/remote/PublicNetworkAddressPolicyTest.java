package com.example.temperate.service.admin.aimodel.icon.remote;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

/**
 * 验证远程图标下载的 DNS 地址策略拒绝私网、元数据和文档保留地址。
 */
final class PublicNetworkAddressPolicyTest {

    @Test
    void acceptsOnlyPublicAddresses() throws Exception {
        assertThatCode(() -> PublicNetworkAddressPolicy.requirePublic(
                new InetAddress[] {InetAddress.getByName("8.8.8.8")}))
                .doesNotThrowAnyException();

        for (String address : new String[] {
                "127.0.0.1",
                "10.0.0.1",
                "169.254.169.254",
                "192.0.2.1",
                "2001:db8::1",
                "fd00::1"
        }) {
            assertThatThrownBy(() -> PublicNetworkAddressPolicy.requirePublic(
                    new InetAddress[] {InetAddress.getByName(address)}))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
