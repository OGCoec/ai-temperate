package com.example.temperate.service.user.aiconversation.model.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;

/**
 * 验证模型生成媒体在实际 HTTP 建连阶段只接受公网 DNS 结果。
 */
final class AiConversationPublicDnsResolverTest {

    @Test
    void rejectsLoopbackAndPrivateAddressesAtConnectionResolution() throws Exception {
        DnsResolver resolver = new AiConversationPublicDnsResolver(
                fixed(
                        address(127, 0, 0, 1),
                        address(10, 0, 0, 8)));

        assertThatThrownBy(() -> resolver.resolve("media.example.test"))
                .isInstanceOf(java.net.UnknownHostException.class);
    }

    @Test
    void returnsDefensiveCopyOfPublicAddresses() throws Exception {
        InetAddress publicAddress = address(8, 8, 8, 8);
        DnsResolver resolver = new AiConversationPublicDnsResolver(
                fixed(publicAddress));

        InetAddress[] first = resolver.resolve("media.example.test");
        InetAddress[] second = resolver.resolve("media.example.test");

        assertThat(first).containsExactly(publicAddress);
        assertThat(second).containsExactly(publicAddress);
        assertThat(first).isNotSameAs(second);
    }

    private static DnsResolver fixed(InetAddress... addresses) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) {
                return addresses.clone();
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }

    private static InetAddress address(int first, int second, int third, int fourth)
            throws Exception {
        return InetAddress.getByAddress(new byte[] {
                (byte) first, (byte) second, (byte) third, (byte) fourth
        });
    }
}
