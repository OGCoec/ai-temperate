package com.example.temperate.web.auth.phonecountry.component;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.web.auth.phonecountry.config.properties.PhoneCountryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 验证可信代理边界、Cloudflare 权威头优先级和公网地址过滤规则的测试。
 */
class TrustedClientIpResolverTest {

    @Test
    void usesCloudflareConnectingIpFromTrustedLoopbackProxy() {
        TrustedClientIpResolver resolver = resolverWithTrustedRanges("127.0.0.1/32,::1/128");
        MockHttpServletRequest request = requestFrom("127.0.0.1");
        request.addHeader("CF-Connecting-IP", "8.8.8.8");

        assertThat(resolver.resolve(request)).contains("8.8.8.8");
    }

    @Test
    void givesCloudflareHeaderPriorityOverForwardedFor() {
        TrustedClientIpResolver resolver = resolverWithTrustedRanges("127.0.0.1/32");
        MockHttpServletRequest request = requestFrom("127.0.0.1");
        request.addHeader("CF-Connecting-IP", "1.1.1.1");
        request.addHeader("X-Forwarded-For", "8.8.8.8");

        assertThat(resolver.resolve(request)).contains("1.1.1.1");
    }

    @Test
    void rejectsMalformedCloudflareHeaderWithoutFallingBack() {
        TrustedClientIpResolver resolver = resolverWithTrustedRanges("127.0.0.1/32");
        MockHttpServletRequest request = requestFrom("127.0.0.1");
        request.addHeader("CF-Connecting-IP", "attacker.example");
        request.addHeader("X-Forwarded-For", "8.8.8.8");
        request.addHeader("X-Real-IP", "1.1.1.1");

        assertThat(resolver.resolve(request)).isEmpty();
    }

    @Test
    void rejectsPrivateCloudflareHeaderWithoutFallingBack() {
        TrustedClientIpResolver resolver = resolverWithTrustedRanges("127.0.0.1/32");
        MockHttpServletRequest request = requestFrom("127.0.0.1");
        request.addHeader("CF-Connecting-IP", "192.168.1.20");
        request.addHeader("X-Forwarded-For", "8.8.8.8");

        assertThat(resolver.resolve(request)).isEmpty();
    }

    @Test
    void usesForwardedForOnlyWhenCloudflareHeaderIsAbsent() {
        TrustedClientIpResolver resolver = resolverWithTrustedRanges("127.0.0.1/32");
        MockHttpServletRequest request = requestFrom("127.0.0.1");
        request.addHeader("X-Forwarded-For", "8.8.8.8, 127.0.0.1");

        assertThat(resolver.resolve(request)).contains("8.8.8.8");
    }

    @Test
    void rejectsEntireForwardedChainWhenAnyLiteralIsMalformed() {
        TrustedClientIpResolver resolver = resolverWithTrustedRanges("127.0.0.1/32");
        MockHttpServletRequest request = requestFrom("127.0.0.1");
        request.addHeader("X-Forwarded-For", "8.8.8.8, attacker.example");

        assertThat(resolver.resolve(request)).isEmpty();
    }

    @Test
    void rejectsPrivateSecurityBoundaryWithoutSearchingFurtherLeft() {
        TrustedClientIpResolver resolver = resolverWithTrustedRanges("127.0.0.1/32");
        MockHttpServletRequest request = requestFrom("127.0.0.1");
        request.addHeader("X-Forwarded-For", "8.8.8.8, 192.168.1.20, 127.0.0.1");

        assertThat(resolver.resolve(request)).isEmpty();
    }

    @Test
    void returnsEmptyWhenForwardedChainContainsOnlyTrustedProxies() {
        TrustedClientIpResolver resolver = resolverWithTrustedRanges("127.0.0.0/8");
        MockHttpServletRequest request = requestFrom("127.0.0.1");
        request.addHeader("X-Forwarded-For", "127.0.0.2, 127.0.0.3");

        assertThat(resolver.resolve(request)).isEmpty();
    }

    @Test
    void doesNotReturnTrustedLoopbackWhenForwardingHeadersAreMissing() {
        TrustedClientIpResolver resolver = resolverWithTrustedRanges("127.0.0.1/32");

        assertThat(resolver.resolve(requestFrom("127.0.0.1"))).isEmpty();
    }

    @Test
    void ignoresForwardedHeadersFromAnUntrustedPublicPeer() {
        TrustedClientIpResolver resolver = resolverWithTrustedRanges("127.0.0.1/32");
        MockHttpServletRequest request = requestFrom("1.1.1.1");
        request.addHeader("CF-Connecting-IP", "8.8.8.8");

        assertThat(resolver.resolve(request)).contains("1.1.1.1");
    }

    @Test
    void rejectsUntrustedPrivateDirectPeer() {
        TrustedClientIpResolver resolver = resolverWithTrustedRanges("127.0.0.1/32");

        assertThat(resolver.resolve(requestFrom("192.168.1.20"))).isEmpty();
    }

    @Test
    void usesXRealIpOnlyWhenCloudflareAndForwardedForAreAbsent() {
        TrustedClientIpResolver resolver = resolverWithTrustedRanges("127.0.0.1/32");
        MockHttpServletRequest request = requestFrom("127.0.0.1");
        request.addHeader("X-Real-IP", "1.1.1.1");

        assertThat(resolver.resolve(request)).contains("1.1.1.1");
    }

    @Test
    void doesNotUseXRealIpWhenForwardedForIsPresentButUnsafe() {
        TrustedClientIpResolver resolver = resolverWithTrustedRanges("127.0.0.1/32");
        MockHttpServletRequest request = requestFrom("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.18.0.1");
        request.addHeader("X-Real-IP", "8.8.8.8");

        assertThat(resolver.resolve(request)).isEmpty();
    }

    @Test
    void supportsPublicIpv6ClientFromTrustedIpv6Loopback() {
        TrustedClientIpResolver resolver = resolverWithTrustedRanges("::1/128");
        MockHttpServletRequest request = requestFrom("::1");
        request.addHeader("CF-Connecting-IP", "2001:4860:4860::8888");

        assertThat(resolver.resolve(request)).contains("2001:4860:4860:0:0:0:0:8888");
    }

    private static TrustedClientIpResolver resolverWithTrustedRanges(String ranges) {
        return new TrustedClientIpResolver(new PhoneCountryProperties(true, "unused.bin", ranges));
    }

    private static MockHttpServletRequest requestFrom(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
