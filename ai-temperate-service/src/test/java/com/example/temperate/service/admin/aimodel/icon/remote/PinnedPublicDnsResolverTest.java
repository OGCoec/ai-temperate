package com.example.temperate.service.admin.aimodel.icon.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;

/**
 * 验证 HTTP 连接阶段会逐次检查 DNS 结果，并拒绝重绑定或夹带私网地址。
 */
final class PinnedPublicDnsResolverTest {

    @Test
    void repeatedResolutionRejectsLaterPrivateRebinding() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DnsResolver delegate = resolver(host -> calls.getAndIncrement() == 0
                ? addresses("8.8.8.8")
                : addresses("127.0.0.1"));
        PinnedPublicDnsResolver resolver = new PinnedPublicDnsResolver(delegate);

        assertThat(resolver.resolve("icons.example.test"))
                .extracting(InetAddress::getHostAddress)
                .containsExactly("8.8.8.8");
        assertThatThrownBy(() -> resolver.resolve("icons.example.test"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void mixedPublicAndPrivateAnswersAreRejectedTogether() {
        DnsResolver delegate = resolver(
                host -> addresses("8.8.8.8", "169.254.169.254"));
        PinnedPublicDnsResolver resolver = new PinnedPublicDnsResolver(delegate);

        assertThatThrownBy(() -> resolver.resolve("icons.example.test"))
                .isInstanceOf(UnknownHostException.class);
    }

    private static InetAddress[] addresses(String... values) throws UnknownHostException {
        InetAddress[] result = new InetAddress[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = InetAddress.getByName(values[index]);
        }
        return result;
    }

    private static DnsResolver resolver(ResolverFunction function) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return function.resolve(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }

    @FunctionalInterface
    private interface ResolverFunction {

        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
